package org.synapseworks.pageharbor

import android.app.Application
import android.graphics.*
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.booklayout.*
import org.synapseworks.pageharbor.document.capture.*
import org.synapseworks.pageharbor.document.cardcopy.CardCopyWorkflowState
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.security.*

class ContinuousCaptureActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File;private lateinit var source:File;private lateinit var bytes:ByteArray
    private val writer=Executors.newSingleThreadExecutor()
    private fun owner()=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java]
    private fun model()=ViewModelProvider(compose.activity)[ContinuousCaptureViewModel::class.java]
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/m10-ui-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"synthetic.jpg")
        val b=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888);try{b.eraseColor(Color.LTGRAY);source.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,92,it)}}finally{b.recycle()};bytes=source.readBytes()
        compose.runOnIdle{
            val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED)lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)?.let{lock.onAuthenticationResult(it.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()
            compose.activity.continuousBackendForTest={file,done->writer.execute{file.writeBytes(bytes);done(true)}}
            compose.activity.installDocumentSessionForTest(DocumentSession(listOf(DocumentPage(DocumentPageId(10),DocumentResource(FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
                filter=DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,adjustments=ManualImageAdjustments(20,-25)))))
        }
    }
    @After fun cleanup(){compose.runOnIdle{compose.activity.cancelContinuousCapture();owner().clearScan()};writer.shutdown();root.deleteRecursively()}
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!node.isDisplayed()) {
            node.performScrollTo()
        }
        node.assertIsDisplayed().performClick()
    }
    private fun openDocumentFromPreview() {
        compose.onNodeWithContentDescription("Add").assertIsDisplayed().performClick()
        compose.onNodeWithText("Scan pages").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CaptureMode.DOCUMENT, model().state!!.mode)
    }

    private fun open() {
        openDocumentFromPreview()
        click("capture-mode-batch")
    }
    private fun capture(n:Int){click("capture-shutter");compose.waitUntil(30_000){model().state?.pages?.size==n&&model().state?.busy==false}}
    @Test fun successiveCaptureAndReviewAppendOnlyOnUseAndKeepExistingEdits() {
        val original=owner().documentSession;val rev=owner().documentRevision;open();capture(1);capture(2)
        assertSame(original,owner().documentSession);click("capture-finish");compose.onNodeWithTag("capture-count").assertTextEquals("2 pages ready")
        val publishedFiles=model().state!!.pages.map{it.file};click("capture-next");click("capture-previous");click("capture-use")
        compose.waitUntil(30_000){owner().documentPages.size==3}
        assertSame(original.pages[0],owner().documentPages[0]);assertEquals(rev+1,owner().documentRevision);assertTrue(publishedFiles.all{it.exists()});assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun cancelAndBackLeaveOriginalSessionAndDiscardCapturedFiles() {
        val original=owner().documentSession;open();capture(1);val file=model().state!!.pages[0].file;click("capture-cancel")
        compose.waitUntil(30_000){!file.exists()};assertSame(original,owner().documentSession)
        open();capture(1);androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(30_000){model().state==null};assertSame(original,owner().documentSession)
    }
    @Test fun recreationRetainsReadyPagesButRequiresExplicitResume() {
        open();capture(1);val file=model().state!!.pages[0].file;compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("capture-count").assertTextEquals("1 page ready");compose.onNodeWithTag("capture-shutter").assertIsNotEnabled()
        compose.runOnIdle{compose.activity.continuousBackendForTest={target,done->writer.execute{target.writeBytes(bytes);done(true)}}}
        click("capture-resume");capture(2);assertTrue(file.exists());click("capture-finish");click("capture-cancel")
    }
    @Test fun finishWhileBusySettlesOneCaptureAndRejectsRapidExtraRequests() {
        open();var done:((Boolean)->Unit)?=null;var file:File?=null
        compose.runOnIdle{compose.activity.continuousBackendForTest={f,cb->file=f;done=cb}}
        click("capture-shutter");compose.waitUntil(30_000){done!=null};compose.onNodeWithTag("capture-shutter").assertIsNotEnabled();click("capture-finish")
        writer.execute{file!!.writeBytes(bytes);done!!(true);done!!(true)}
        compose.waitUntil(30_000){model().state?.phase==CapturePhase.REVIEW};compose.onNodeWithTag("capture-count").assertTextEquals("1 page ready");click("capture-cancel")
    }
    @Test fun missingCapturedFileCannotPublishAPartialBatchOrReplaceTheOriginal() {
        val original=owner().documentSession;val revision=owner().documentRevision
        open();capture(1);val file=model().state!!.pages.single().file;click("capture-finish")
        assertTrue(file.delete());click("capture-use");compose.waitUntil(30_000){model().state==null}
        assertSame(original,owner().documentSession);assertEquals(revision,owner().documentRevision)
    }
    @Test fun appLockHidesCaptureAndRequiresResumeWithoutLosingReadyPages() {
        open();capture(1);val file=model().state!!.pages.single().file
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil(30_000){model().state?.paused==true}
        compose.onAllNodesWithTag("capture-shutter").assertCountEquals(0);assertTrue(file.exists())
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];val attempt=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(attempt.id,AppLockAuthenticationResult.Success);lock.disableAfterAuthentication()}
        compose.onNodeWithTag("capture-shutter").assertIsNotEnabled();click("capture-resume");capture(2);click("capture-cancel")
    }
    @Test fun homeOpensDocumentWithBatchAndBookAvailableBeforeCapture() {
        compose.runOnIdle{owner().clearScan();owner().screen=org.synapseworks.pageharbor.ui.PageHarborScreen.Home}
        compose.onNodeWithContentDescription("Scan").performClick()
        assertEquals(CaptureMode.DOCUMENT, model().state!!.mode)
        compose.onNodeWithTag("capture-mode-book").assertExists()
        click("capture-mode-batch")
        capture(1)
        compose.onNodeWithTag("capture-mode-document").assertIsNotEnabled()
        click("capture-finish");click("capture-use")
        compose.waitUntil(30_000){owner().documentPages.size==1}
    }

    @Test fun manualDocumentCapturesOnePageRetakesAndOpensReviewedCrop() {
        val original = owner().documentSession
        openDocumentFromPreview()
        compose.onNodeWithContentDescription("Capture page").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { model().state?.phase == CapturePhase.REVIEW }
        assertSame(original, owner().documentSession)
        compose.onAllNodesWithTag("capture-more").assertCountEquals(0)
        val retired = model().state!!.pages.single().file
        click("capture-replace")
        compose.onNodeWithContentDescription("Capture page").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { model().state?.phase == CapturePhase.REVIEW && !retired.exists() }
        assertEquals(1, model().state!!.pages.size)
        compose.onNodeWithText("Review corners").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("import-crop").fetchSemanticsNodes().isNotEmpty() }
        click("import-crop-keep")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("import-crop").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, owner().documentPages.size)
        assertSame(original.pages.first(), owner().documentPages.first())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun emptyCameraCanOpenTheTwoSidedCardToolAndReturnToTheSameDocument() {
        val original = owner().documentSession
        val revision = owner().documentRevision
        openDocumentFromPreview()
        click("capture-open-card-copy")
        compose.waitUntil(30_000) { compose.activity.cardCopyWorkflowStateForTest() is CardCopyWorkflowState.AcquireSides }
        assertNull(model().state)
        compose.onNodeWithText("1 of 2 · Front of ID").assertIsDisplayed()
        compose.onNodeWithTag("id-shutter").assertIsDisplayed()
        compose.runOnIdle { compose.activity.cardCopyWorkflowForTest().back() }
        compose.waitUntil(30_000) { compose.activity.cardCopyWorkflowStateForTest() == CardCopyWorkflowState.Hidden }
        assertSame(original, owner().documentSession)
        assertEquals(revision, owner().documentRevision)
        open()
        val captureOwner = model().state!!.owner
        capture(1)
        compose.onNodeWithTag("capture-open-card-copy").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.openCardCopyFromCamera(captureOwner) }
        assertEquals(CardCopyWorkflowState.Hidden, compose.activity.cardCopyWorkflowStateForTest())
        assertEquals(1, model().state!!.pages.size)
        click("capture-cancel")
        assertSame(original, owner().documentSession)
    }

    private fun bookModel() = ViewModelProvider(compose.activity)[BookSplitViewModel::class.java]

    private fun waitForHalves() {
        compose.waitUntil(45_000) {
            bookModel().state?.let { it.work == null && it.preview?.halves?.size == 2 } == true
        }
    }

    private fun openCapturedBook() {
        val existing = owner().documentSession
        openDocumentFromPreview()
        click("capture-mode-book")
        capture(1)
        assertSame(existing, owner().documentSession)
        assertEquals(CapturePhase.REVIEW, model().state!!.phase)
        click("capture-use")
        waitForHalves()
        val review = bookModel().state!!
        assertTrue(review.capturedSpread)
        assertEquals(1, review.sourceCount)
        assertSame(owner().documentPages.last(), review.current!!.page)
        compose.onAllNodesWithTag("book-all").assertCountEquals(0)
    }

    private fun assertPreviewSides(leftOutput: Int, rightOutput: Int) {
        val left = compose.onNodeWithTag("book-half-$leftOutput").performScrollTo().assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("book-half-$rightOutput").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals("Image tops must align even if only one heading wraps", left.top.value, right.top.value, 0.01f)
        assertTrue("Physical left must stay left", left.right <= right.left)
    }

    @Test fun bookCaptureKeepsTheExactSpreadAndOffersTwoPreviewsEvenWithoutADetectedGutter() {
        // The synthetic camera produces a blank gray JPEG. The centered gutter requires review.
        val first = owner().documentPages.single()
        openCapturedBook()
        val captured = owner().documentPages.last()
        assertNotEquals(BookDetectionStatus.CONFIDENT, bookModel().state!!.current!!.analysis.detection.status)
        assertPreviewSides(1, 2)
        click("book-rtl")
        waitForHalves()
        assertPreviewSides(2, 1)
        click("book-cancel")
        compose.waitUntil(30_000) { bookModel().state == null && !owner().hasActiveDocumentSessionLeases() }
        assertEquals(2, owner().documentPages.size)
        assertSame(first, owner().documentPages.first())
        assertSame(captured, owner().documentPages.last())
        assertArrayEquals(bytes, File(requireNotNull(captured.source.ownedTemporaryFile).path).readBytes())
    }

    @Test fun bookCaptureSplitsOnlyTheNewSpreadInReviewedOrder() {
        val first = owner().documentPages.single()
        val reference = BookTestFixtures.source(root, index = 1, id = 901)
        val cameraInput = File(android.net.Uri.parse(reference.source.reference).path!!).readBytes()
        compose.runOnIdle {
            compose.activity.continuousBackendForTest = { destination, done ->
                writer.execute { destination.writeBytes(cameraInput); done(true) }
            }
        }
        openCapturedBook()
        compose.onNodeWithTag("book-top").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertTrue(it(.4f)) }
        waitForHalves()
        compose.onNodeWithTag("book-bottom").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertTrue(it(.4f)) }
        waitForHalves()
        click("book-rtl")
        waitForHalves()
        click("book-apply")
        compose.waitUntil(45_000) { owner().documentPages.size == 3 && bookModel().state == null }
        assertSame(first, owner().documentPages.first())
        val (right, left) = owner().documentPages.drop(1)
        assertTrue(right.imageMetadata.width!! > left.imageMetadata.width!!)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun batchReviewRetakesSelectedPageAndTransfersReviewedOrder() {
        val original = owner().documentSession
        open()
        capture(1)
        capture(2)
        click("capture-finish")
        val prior = model().state!!.pages.map { it.file }
        click("capture-move-later")
        assertEquals(listOf(prior[1], prior[0]), model().state!!.pages.map { it.file })
        click("capture-replace")
        click("capture-shutter")
        compose.waitUntil(30_000) { model().state?.phase == CapturePhase.REVIEW }
        val reviewed = model().state!!.pages.map { it.file }
        assertEquals(prior[1], reviewed.first())
        assertNotEquals(prior[0], reviewed.last())
        compose.waitUntil(30_000) { !prior[0].exists() }
        click("capture-use")
        compose.waitUntil(30_000) { owner().documentPages.size == 3 }
        assertSame(original.pages.single(), owner().documentPages.first())
        assertEquals(reviewed.map { it.path }, owner().documentPages.drop(1).map { it.source.ownedTemporaryFile?.path })
    }


    @Test fun thumbnailsJumpAndMoveDirectlyThenRetakeSelectedPage() {
        open()
        capture(1)
        capture(2)
        capture(3)
        click("capture-finish")
        val prior = model().state!!.pages.map { it.file }
        click("capture-open-grid")
        compose.onNodeWithTag("capture-grid-page-3").performClick().assertIsSelected()
        compose.onNodeWithTag("capture-grid-move").performClick()
        compose.onNodeWithText("Position (1–3)").performTextInput("1")
        compose.onNodeWithText("Move page").performClick()
        assertEquals(listOf(prior[2], prior[0], prior[1]), model().state!!.pages.map { it.file })
        compose.onNodeWithTag("capture-grid-retake").performClick()
        click("capture-shutter")
        compose.waitUntil(30_000) { model().state?.phase == CapturePhase.REVIEW }
        val reviewed = model().state!!.pages.map { it.file }
        assertNotEquals(prior[2], reviewed.first())
        assertEquals(prior.take(2), reviewed.drop(1))
        click("capture-cancel")
    }

}
