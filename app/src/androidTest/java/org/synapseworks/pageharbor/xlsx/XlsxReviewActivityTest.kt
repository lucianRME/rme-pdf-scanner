package org.synapseworks.pageharbor.xlsx

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.workflow.WorkflowFaultProvider

class XlsxReviewActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private lateinit var vm:XlsxViewModel
    private fun open() {
        val grid=FrozenXlsxCorpus.table().grid
        val state=TableState("synthetic-source",1,TablePhase.REVIEW,grid=grid,review=TableReview.create(grid),confirmed=true)
        vm.foreground(true);vm.table(state,XlsxInput("Manual entry"));vm.confirmTable(0,true)
    }
    private fun scroll(tag:String):SemanticsNodeInteraction {compose.onNodeWithTag("xlsx-screen").performScrollToNode(hasTestTag(tag));return compose.onNodeWithTag(tag)}
    private fun generate(){compose.runOnIdle{vm.review(true);vm.generate()};compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}}
    @Before fun prepare(){compose.runOnIdle{
        val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
        if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
        lock.disableAfterAuthentication();vm=ViewModelProvider(compose.activity)[XlsxViewModel::class.java];open()
    }}
    @After fun cleanup(){compose.runOnIdle{vm.close()};WorkflowFaultProvider.reset(app,"synthetic.pdf")}
    @Test fun actualCheckboxReviewCorrectionAndGenerationAreReachable(){
        scroll("xlsx-confirm-table-0").performClick();assertFalse(vm.book.tables.single().confirmed)
        compose.onNodeWithTag("xlsx-confirm-table-0").performClick();assertTrue(vm.book.tables.single().confirmed)
        scroll("xlsx-review").performClick()
        scroll("xlsx-generate").performClick();compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}
        val old=vm.prepared;scroll("xlsx-cell-0").performTextReplacement("Corrected literal =1+1")
        assertNull(vm.prepared);assertFalse(vm.book.tables.single().confirmed);assertNotEquals(old!!.book.tables.single().grid.rows.first().first().value,vm.book.tables.single().grid.rows.first().first().value)
    }
    @Test fun unverifiableSourceDeclineIsVisibleAndDoesNotAdoptContent(){
        val before=vm.book.owned()
        compose.runOnIdle{vm.collect{};val grid=FrozenXlsxCorpus.table().grid
            vm.table(TableState("unverified",1,TablePhase.REVIEW,grid=grid,review=TableReview.create(grid),confirmed=true),XlsxInput("Unverified image"))}
        assertTrue(vm.visible);assertFalse(vm.collecting);assertEquals(before,vm.book)
        scroll("xlsx-message").assertTextContains("Workbook source declined",substring=true)
    }
    @Test fun exhaustedOwnerDirectoryBudgetDeclinesWithoutCreatingMoreDirectories(){
        val base=java.io.File(app.noBackupFilesDir,"xlsx-v1").apply{mkdirs()};val created=mutableListOf<java.io.File>()
        try{
            while(base.listFiles().orEmpty().size<32){val d=java.io.File(base,"synthetic-budget-${java.util.UUID.randomUUID()}");assertTrue(d.mkdir());created+=d}
            val count=base.listFiles().orEmpty().size
            compose.runOnIdle{vm.review(true);vm.generate()};compose.waitUntil(30000){!vm.busy}
            assertNull(vm.prepared);assertEquals(count,base.listFiles().orEmpty().size)
            assertTrue(vm.message.orEmpty().startsWith("Workbook declined"))
        }finally{created.forEach{assertTrue(it.delete())}}
    }
    @Test fun competingShareOwnersRespectTheCombinedCopyBudget(){
        generate();val base=java.io.File(app.cacheDir,"shared-pdfs").apply{mkdirs()}
        val original=base.listFiles().orEmpty().filter{it.name.startsWith("xlsx-")}.map{it.name}.toSet()
        var other:XlsxViewModel?=null;val launches=java.util.concurrent.atomic.AtomicInteger()
        try{
            while(base.listFiles().orEmpty().count{it.name.startsWith("xlsx-")}<3)assertTrue(java.io.File(base,"xlsx-synthetic-${java.util.UUID.randomUUID()}").mkdir())
            assertEquals(3,base.listFiles().orEmpty().count{it.name.startsWith("xlsx-")})
            compose.runOnIdle{other=XlsxViewModel(app);other!!.update(vm.book);other!!.review(true);other!!.generate()}
            compose.waitUntil(30000){other!!.prepared!=null&&!other!!.busy}
            compose.runOnIdle{vm.share{launches.incrementAndGet()};other!!.share{launches.incrementAndGet()}}
            compose.waitUntil(30000){!vm.busy&&!other!!.busy}
            assertEquals(1,launches.get());assertEquals(4,base.listFiles().orEmpty().count{it.name.startsWith("xlsx-")})
        }finally{compose.runOnIdle{other?.close()};base.listFiles().orEmpty().filter{it.name.startsWith("xlsx-")&&it.name !in original}.forEach{it.deleteRecursively()}}
    }
    @Test fun recreationDiscardsDraftWithoutSerializingText(){
        val secret="SYNTHETIC-M33-PRIVATE-DRAFT"
        scroll("xlsx-cell-0").performTextReplacement(secret)
        val state=Bundle();compose.runOnIdle{instrumentation.callActivityOnSaveInstanceState(compose.activity,state)}
        val parcel=Parcel.obtain();val data=try{state.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        assertFalse(String(data,Charsets.UTF_16LE).contains(secret));assertFalse(String(data,Charsets.UTF_8).contains(secret))
        compose.activityRule.scenario.recreate();compose.waitUntil(30000){!vm.visible&&vm.book.tables.isEmpty()&&vm.prepared==null}
    }
    @Test fun staleSaveCannotRetargetNewReviewWithTheSameName(){
        generate();val old=vm.prepared!!.file;val target=WorkflowFaultProvider.reset(app,vm.name);WorkflowFaultProvider.mime=XLSX_MIME
        compose.runOnIdle{assertNotNull(vm.beginSave());vm.close()};compose.waitUntil(30000){!old.exists()}
        compose.runOnIdle{open()};generate();val next=vm.prepared
        compose.runOnIdle{assertNull(vm.beginSave());vm.destination(target)}
        assertSame(next,vm.prepared);assertEquals(0,WorkflowFaultProvider.opened)
        compose.runOnIdle{assertNotNull(vm.beginSave());vm.destination(null)}
    }
    @Test fun appLockDiscardsPendingWriteAndDraft(){
        generate();val old=vm.prepared!!.file
        compose.runOnIdle{assertNotNull(vm.beginSave());val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil(30000){!vm.visible&&vm.book.tables.isEmpty()&&!old.exists()};assertFalse(vm.picker)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val request=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(request.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
    }
}
