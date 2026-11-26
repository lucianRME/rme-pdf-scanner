package org.synapseworks.pageharbor.workflow

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.security.*

class WorkflowActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as Application
    private val vm get()=ViewModelProvider(compose.activity)[WorkflowViewModel::class.java]
    private val session get()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    private lateinit var root:File
    private lateinit var source:File
    @Before fun setup() {
        root=File(app.cacheDir,"shared-pdfs/m30-ui-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"source.png")
        source.writeBytes(instrumentation.context.assets.open("m29/medium-left-512-reference.png").use{it.readBytes()})
        compose.runOnIdle {
            val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java]
            if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)}
            lock.disableAfterAuthentication()
            vm.queue.data().jobs.forEach { vm.queue.keepExternalAndCancel(it.id);vm.queue.remove(it.id) };vm.queue.data().recipes.forEach { vm.queue.revoke(it.id);vm.queue.deleteRecipe(it.id) }
            val resource=DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL)
            compose.activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(1),resource,DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384)))))
            vm.open();vm.create();vm.saveRecipe();vm.select(vm.data.recipes.single())
        }
    }
    @After fun after() { compose.runOnIdle { vm.close();session.clearScan();vm.queue.data().jobs.forEach { vm.queue.keepExternalAndCancel(it.id);vm.queue.remove(it.id) };vm.queue.data().recipes.forEach { vm.queue.revoke(it.id);vm.queue.deleteRecipe(it.id) } };root.deleteRecursively() }
    private fun capture() { compose.onNodeWithTag("workflow-capture").performScrollTo().performClick();compose.waitUntil(45000){vm.review!=null && vm.preview!=null && !vm.busy};assertTrue(vm.message.orEmpty(),vm.review!=null) }
    @Test fun reviewedSnapshotSurvivesLaterSourceEditRecipeEditAndHistoryRelease() {
        val original=source.readBytes();capture();val r=vm.review!!;val frozen=r.captured.output.readBytes()
        compose.onNodeWithTag("workflow-enqueue").performScrollTo().performClick();compose.waitUntil{vm.data.jobs.size==1}
        val job=vm.data.jobs.single()
        compose.runOnIdle { vm.select(vm.data.recipes.single()) }
        compose.onNodeWithTag("workflow-capture").performScrollTo().performClick()
        compose.waitUntil(45000){vm.review!=null || vm.message?.contains("already")==true}
        assertNull("A repeated submission must not preview a different counter/name",vm.review)
        assertEquals(1,vm.data.jobs.size);assertEquals(2,vm.data.recipes.single().nextCounter)
        compose.runOnIdle { check(session.rotatePageClockwise(1));vm.edit(vm.data.recipes.single());vm.change(vm.draft!!.copy(custom="Another"));vm.saveRecipe();session.clearScan() }
        source.appendBytes(byteArrayOf(0));assertArrayEquals(frozen,vm.queue.store.pdf(job.id).readBytes());assertEquals(r.name,vm.queue.data().jobs.single().name);assertEquals(r.revision,vm.queue.data().jobs.single().revision)
        source.writeBytes(original);assertArrayEquals(original,source.readBytes());assertFalse(session.hasActiveDocumentSessionLeases())
    }
    @Test fun recreationAndBackgroundDiscardReviewWithoutPersistingDraftText() {
        val secret="M30EphemeralSyntheticTitleQ1"
        compose.runOnIdle { vm.change(vm.draft!!.copy(custom=secret));vm.saveRecipe();vm.select(vm.data.recipes.single()) }
        capture();val work=vm.review!!.captured.directory
        val bundle=Bundle();compose.runOnIdle { instrumentation.callActivityOnSaveInstanceState(compose.activity,bundle) }
        val parcel=Parcel.obtain();val bytes=try{bundle.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        assertFalse(String(bytes,Charsets.UTF_16LE).contains(secret));assertFalse(String(bytes,Charsets.UTF_8).contains(secret))
        compose.activityRule.scenario.recreate();compose.waitUntil{vm.review==null && !work.exists()};assertFalse(session.hasActiveDocumentSessionLeases())
        compose.runOnIdle{vm.select(vm.data.recipes.single())};capture();val second=vm.review!!.captured.directory
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil{vm.review==null && !second.exists()}
    }
    @Test fun lockCancelsCaptureAndStaleManualResultCannotWrite() {
        capture();val work=vm.review!!.captured.directory
        compose.runOnIdle { val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow() }
        compose.waitUntil{!vm.visible && vm.review==null && !work.exists()};assertFalse(vm.queue.active)
        val original=source.readBytes();compose.runOnIdle{vm.manualResult(android.net.Uri.fromFile(source))};assertArrayEquals(original,source.readBytes())
        compose.runOnIdle { val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val request=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(request.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication() }
    }
    @Test fun ocrTitleNeedsExplicitConfirmationAndFutureProfilesStayUnavailable() {
        val before=vm.draft!!.custom
        compose.runOnIdle { vm.titleSuggestion(org.synapseworks.pageharbor.ocr.OcrResult(listOf(org.synapseworks.pageharbor.ocr.OcrPageResult(0,"Northwind Supplies\nInvoice\nInvoice date: 2026-11-08")))) }
        assertNotNull(vm.suggestion);assertEquals(before,vm.draft!!.custom)
        compose.onNodeWithText("Confirm suggested title").performScrollTo().performClick()
        assertNotEquals(before,vm.draft!!.custom)
        compose.onNodeWithText(WorkflowPreset.OFFICE.label).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { vm.saveRecipe() };assertFalse(vm.data.recipes.single().enabled)
    }
    @Test fun archivalFlatteningNeedsExplicitAcceptanceAndCancelledReviewResetsIt() {
        compose.runOnIdle { vm.edit(vm.data.recipes.single());vm.change(vm.draft!!.copy(preset=WorkflowPreset.ARCHIVAL));vm.saveRecipe();vm.select(vm.data.recipes.single()) }
        capture();compose.onNodeWithTag("archival-notice").assertTextContains("PDF/A-1b image-only",substring=true)
        compose.onNodeWithTag("workflow-enqueue").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { vm.enqueue() };assertTrue(vm.data.jobs.isEmpty())
        compose.onNodeWithTag("archival-accept").performScrollTo().performClick()
        compose.onNodeWithTag("workflow-enqueue").assertIsEnabled()
        val old=vm.review!!.captured.directory
        compose.onNodeWithText("Cancel capture").performScrollTo().performClick();assertFalse(old.exists());assertFalse(vm.archivalAccepted)
        capture();assertFalse(vm.archivalAccepted);compose.onNodeWithTag("workflow-enqueue").performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.recreate();compose.waitUntil { vm.review==null };assertFalse(vm.archivalAccepted);assertFalse(session.hasActiveDocumentSessionLeases())
    }
    @Test fun changedEncryptedReviewCannotEnqueueCipherBytes() {
        compose.runOnIdle { vm.edit(vm.data.recipes.single());vm.change(vm.draft!!.copy(preset=WorkflowPreset.ENCRYPTED));vm.saveRecipe();vm.select(vm.data.recipes.single()) }
        compose.onNodeWithTag("workflow-password").performScrollTo().performTextInput("Synthetic-M30-secret")
        compose.runOnIdle{compose.activity.currentFocus?.clearFocus();androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
        capture();val r=vm.review!!;assertNotEquals(r.captured.preview,r.captured.output)
        r.captured.preview.appendBytes(byteArrayOf(1))
        compose.onNodeWithTag("workflow-enqueue").performScrollTo().performClick()
        assertTrue(vm.data.jobs.isEmpty());assertNotNull(vm.message);assertEquals(1,vm.data.recipes.single().nextCounter)
    }
    @Test fun staleCaptureAndCancelNeverPublishOrReserveCounter() {
        val counter=vm.data.recipes.single().nextCounter
        compose.onNodeWithTag("workflow-capture").performScrollTo().performClick()
        compose.runOnIdle { check(session.rotatePageClockwise(1));vm.discardReview() }
        compose.waitUntil{!vm.busy};assertTrue(vm.data.jobs.isEmpty());assertEquals(counter,vm.data.recipes.single().nextCounter)
        compose.runOnIdle{vm.select(vm.data.recipes.single())};capture();val dir=vm.review!!.captured.directory
        compose.runOnIdle{vm.discardReview()};assertFalse(dir.exists());assertTrue(vm.data.jobs.isEmpty())
    }
}
