package org.synapseworks.pageharbor.docx

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.security.*

class DocxReviewActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as Application
    private lateinit var vm:DocxViewModel
    private lateinit var root:File
    private lateinit var source:File
    @Before fun prepare(){
        root=File(app.cacheDir,"shared-pdfs/m32-review-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"source.png").apply{writeBytes(instrumentation.context.assets.open("m32/letter-0.png").use{it.readBytes()})}
        compose.runOnIdle {
            val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
            lock.disableAfterAuthentication()
            vm=ViewModelProvider(compose.activity)[DocxViewModel::class.java];vm.foreground(true)
            vm.open(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584)))),OcrResult(FrozenDocxCorpus.ocr(instrumentation.context.assets.open("m32/letter.tsv").bufferedReader().use{it.readText()})),9,"synthetic-review",{true},{})
        };compose.waitUntil(30000){vm.capture!=null&&vm.preview!=null&&!vm.busy}
    }
    @After fun cleanup(){compose.runOnIdle{vm.close()};root.deleteRecursively()}
    @Test fun actualTextCorrectionIsMarkedAndNotSavedInActivityStateThenRecreationDiscards(){
        val secret="M32EphemeralCorrectionQ27";val original=source.readBytes();val work=vm.capture!!.frozen.directory
        compose.onNodeWithTag("docx-text-0").performScrollTo().performTextReplacement(secret)
        assertEquals(secret,vm.document!!.pages.single().blocks.first().text);assertTrue(vm.document!!.pages.single().blocks.first().corrected)
        assertNotEquals(secret,vm.document!!.pages.single().lines.first().text)
        val state=Bundle();compose.runOnIdle{instrumentation.callActivityOnSaveInstanceState(compose.activity,state)}
        val parcel=Parcel.obtain();val bytes=try{state.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        assertFalse(String(bytes,Charsets.UTF_16LE).contains(secret));assertFalse(String(bytes,Charsets.UTF_8).contains(secret))
        compose.activityRule.scenario.recreate();compose.waitUntil(30000){!vm.visible&&vm.document==null&&!work.exists()};assertArrayEquals(original,source.readBytes())
    }
    @Test fun stalePickerCannotRetargetAReopenedReviewWithTheSameName(){
        compose.runOnIdle{vm.reviewPage(true);vm.generate()};compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}
        val work=vm.capture!!.frozen.directory
        compose.runOnIdle{assertNotNull(vm.beginSave());vm.close()};compose.waitUntil(30000){!work.exists()}
        compose.runOnIdle{vm.foreground(true);vm.open(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584)))),OcrResult(FrozenDocxCorpus.ocr(instrumentation.context.assets.open("m32/letter.tsv").bufferedReader().use{it.readText()})),11,"new-review",{true},{})}
        compose.waitUntil(30000){vm.capture!=null&&vm.preview!=null&&!vm.busy};compose.runOnIdle{vm.reviewPage(true);vm.generate()};compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}
        val prepared=vm.prepared
        val target=org.synapseworks.pageharbor.workflow.WorkflowFaultProvider.reset(app,"Document.docx");org.synapseworks.pageharbor.workflow.WorkflowFaultProvider.mime=DOCX_MIME
        compose.runOnIdle{assertNull(vm.beginSave());vm.destination(target)}
        assertSame(prepared,vm.prepared);assertEquals(0,org.synapseworks.pageharbor.workflow.WorkflowFaultProvider.opened)
        compose.runOnIdle{assertNotNull(vm.beginSave());vm.destination(null)}
    }
    @Test fun ageCleanupCannotDeleteAnotherLiveReviewOwner(){
        val work=vm.capture!!.frozen.directory;val ownerRoot=work.parentFile!!
        assertTrue(ownerRoot.setLastModified(System.currentTimeMillis()-25L*60*60*1000))
        val store=androidx.lifecycle.ViewModelStore()
        try{compose.runOnIdle{ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[DocxViewModel::class.java]};assertTrue(work.isDirectory);assertNotNull(vm.document)}
        finally{store.clear();ownerRoot.setLastModified(System.currentTimeMillis())}
    }
    @Test fun paragraphSplitKeepsExactSourceOccurrencesAndCorrectedTextCannotBeReplacedByRawTableProposal(){
        val source=vm.document!!.pages.single();val paragraph=source.blocks.first{it.sourceIds.size==2}
        compose.runOnIdle{vm.split(paragraph.id)}
        val corrected=vm.document!!.pages.single();assertEquals(source.lines.map{it.id}.toSet(),corrected.blocks.flatMap{it.sourceIds}.toSet())
        paragraph.sourceIds.forEach{id->assertEquals(listOf(id),corrected.blocks.single{it.sourceIds.contains(id)}.sourceIds)}
        val tableSource=DocxReconstructor.page(FrozenDocxCorpus.ocr(instrumentation.context.assets.open("m32/table.tsv").bufferedReader().use{it.readText()}).single())
        val original=tableSource.blocks.first{it.box?.top?.let{y->y>.15f&&y<.27f}==true}
        val changed=tableSource.copy(blocks=tableSource.blocks.map{if(it.id==original.id)it.copy(text="User corrected value",corrected=true)else it})
        assertThrows(IllegalArgumentException::class.java){DocxReconstructor.table(changed,FrozenDocxCorpus.tableRegion("table")!!)}
    }
    @Test fun backgroundDuringOcrPreparationCancelsAndCannotPublishLateResult(){
        compose.runOnIdle{vm.close()};compose.waitUntil(30000){vm.capture==null}
        val gate=kotlinx.coroutines.CompletableDeferred<OcrResult?>();var released=false
        compose.runOnIdle{vm.foreground(true);vm.open(DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),1224,1584)))),null,10,"pending-ocr",{true},{released=true},ocrProvider={gate.await()})}
        assertTrue(vm.visible&&vm.busy)
        try {
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val deadline=android.os.SystemClock.elapsedRealtime()+10000
            while(!released&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(20)
        } finally {gate.complete(OcrResult(emptyList()));compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)}
        assertTrue(released);assertNull(vm.capture);assertNull(vm.document);assertFalse(vm.visible);assertFalse(vm.busy)
    }
    @Test fun appLockInvalidatesPendingSaveAndDeletesPrivateDraft(){
        val work=vm.capture!!.frozen.directory
        compose.runOnIdle{vm.reviewPage(true);vm.generate()};compose.waitUntil(30000){vm.prepared!=null&&!vm.busy}
        compose.runOnIdle{assertNotNull(vm.beginSave());val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil(30000){!vm.visible&&vm.document==null&&!work.exists()};assertFalse(vm.picker)
        compose.runOnIdle{vm.destination(Uri.fromFile(source));val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val request=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(request.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
        assertTrue(source.length()>0)
    }
}
