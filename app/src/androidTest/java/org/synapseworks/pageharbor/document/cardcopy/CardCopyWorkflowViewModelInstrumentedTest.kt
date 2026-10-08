package org.synapseworks.pageharbor.document.cardcopy

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

class CardCopyWorkflowViewModelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application = context.applicationContext as Application
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "card-copy-workflow-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        listOf(CARD_COPY_SOURCE_DIRECTORY, CARD_COPY_OUTPUT_DIRECTORY).forEach { name ->
            File(context.cacheDir, name).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun acquisitionCancellationFailureReplacementAndRemovalPreserveTheOtherSide() = runBlocking {
        val viewModel = CardCopyWorkflowViewModel(application, SavedStateHandle())
        viewModel.open()
        assertTrue(viewModel.state is CardCopyWorkflowState.AcquireSides)

        assertTrue(viewModel.beginAcquisition(CardCopySide.FRONT, CardCopyAcquisition.IMPORT))
        viewModel.onAcquisitionResult(null)
        assertSame(
            CardCopySideState.Empty,
            (viewModel.state as CardCopyWorkflowState.AcquireSides).front,
        )

        val firstFront = import(viewModel, CardCopySide.FRONT, fixture("front-one", Color.RED))
        assertTrue(viewModel.beginAcquisition(CardCopySide.BACK, CardCopyAcquisition.SCAN))
        viewModel.onAcquisitionFailure()
        val failed = viewModel.state as CardCopyWorkflowState.AcquireSides
        assertEquals(firstFront.file, failed.front.readySource()?.file)
        assertTrue(failed.back is CardCopySideState.Failed)

        val back = import(viewModel, CardCopySide.BACK, fixture("back", Color.BLUE))
        val oldFrontDirectory = firstFront.ownedDirectory
        val replacementFront = import(viewModel, CardCopySide.FRONT, fixture("front-two", Color.GREEN))
        assertFalse(oldFrontDirectory.exists())
        val ready = viewModel.state as CardCopyWorkflowState.AcquireSides
        assertEquals(replacementFront.file, ready.front.readySource()?.file)
        assertEquals(back.file, ready.back.readySource()?.file)

        viewModel.continueToReview()
        assertTrue(viewModel.state is CardCopyWorkflowState.ReviewSides)
        viewModel.remove(CardCopySide.FRONT)
        val removed = viewModel.state as CardCopyWorkflowState.AcquireSides
        assertSame(CardCopySideState.Empty, removed.front)
        assertEquals(back.file, removed.back.readySource()?.file)
        assertTrue(back.file.exists())
        viewModel.close()
    }

    @Test
    fun repeatedSwapRotationLayoutPreviewPrepareAndSaveCancelFollowExplicitStates() = runBlocking {
        val handle = SavedStateHandle()
        val viewModel = CardCopyWorkflowViewModel(application, handle)
        viewModel.open()
        val front = import(viewModel, CardCopySide.FRONT, fixture("front", Color.RED))
        val back = import(viewModel, CardCopySide.BACK, fixture("back", Color.BLUE))
        viewModel.continueToReview()

        viewModel.swap()
        var review = viewModel.state as CardCopyWorkflowState.ReviewSides
        assertEquals(back.file, review.front.readySource()?.file)
        assertEquals(front.file, review.back.readySource()?.file)
        viewModel.swap()
        review = viewModel.state as CardCopyWorkflowState.ReviewSides
        assertEquals(front.file, review.front.readySource()?.file)
        assertEquals(back.file, review.back.readySource()?.file)

        viewModel.rotate(CardCopySide.FRONT, clockwise = true)
        review = viewModel.state as CardCopyWorkflowState.ReviewSides
        assertEquals(DocumentPageRotation.DEGREES_90, review.front.readySource()?.rotation)
        viewModel.rotate(CardCopySide.FRONT, clockwise = false)
        assertEquals(
            DocumentPageRotation.DEGREES_0,
            (viewModel.state as CardCopyWorkflowState.ReviewSides).front.readySource()?.rotation,
        )

        viewModel.continueToLayout()
        viewModel.updatePageSize(CardCopyPageSize.LETTER)
        viewModel.updateLayout(CardCopyLayout.HORIZONTAL)
        viewModel.updateSpacing(CardCopySpacing.COMPACT)
        viewModel.updateAlignment(CardCopyAlignment.END)
        val layout = viewModel.state as CardCopyWorkflowState.Layout
        assertEquals(
            CardCopySettings(
                CardCopyPageSize.LETTER,
                CardCopyLayout.HORIZONTAL,
                CardCopyAlignment.END,
                CardCopySpacing.COMPACT,
            ),
            layout.settings,
        )
        viewModel.showPreview()
        assertTrue(viewModel.state is CardCopyWorkflowState.Preview)
        viewModel.prepare()
        waitUntil { viewModel.state is CardCopyWorkflowState.Prepared }
        val prepared = viewModel.state as CardCopyWorkflowState.Prepared
        assertTrue(prepared.prepared.file.isFile)
        viewModel.markSavePickerOpen()
        assertSame(
            CardCopySaveStatus.ChoosingDestination,
            (viewModel.state as CardCopyWorkflowState.Prepared).saveStatus,
        )
        viewModel.onSaveDestination(null)
        val afterCancel = viewModel.state as CardCopyWorkflowState.Prepared
        assertSame(CardCopySaveStatus.Idle, afterCancel.saveStatus)
        assertEquals(prepared.prepared.file, afterCancel.prepared.file)

        viewModel.back()
        assertTrue(viewModel.state is CardCopyWorkflowState.Layout)
        assertFalse(prepared.prepared.workingDirectory.exists())
        viewModel.close()
    }

    @Test
    fun savedActiveMarkerRecoversAsRetryMessageWithoutBitmapState() {
        val restored = CardCopyWorkflowViewModel(
            application,
            SavedStateHandle(mapOf("card_copy_workflow_active" to true)),
        )

        val state = restored.state as CardCopyWorkflowState.Failed
        assertEquals(CardCopyFailure.PROCESS_INTERRUPTED, state.reason)
        assertEquals(null, state.front)
        assertEquals(null, state.back)
        restored.close()
    }

    private suspend fun import(
        viewModel: CardCopyWorkflowViewModel,
        side: CardCopySide,
        file: File,
    ): CardCopySource {
        assertTrue(viewModel.beginAcquisition(side, CardCopyAcquisition.IMPORT))
        viewModel.onAcquisitionResult(Uri.fromFile(file))
        waitUntil {
            val state = viewModel.state as? CardCopyWorkflowState.AcquireSides
            state?.let { if (side == CardCopySide.FRONT) it.front else it.back } is CardCopySideState.Ready
        }
        val state = viewModel.state as CardCopyWorkflowState.AcquireSides
        return requireNotNull(
            (if (side == CardCopySide.FRONT) state.front else state.back).readySource(),
        )
    }

    private suspend fun waitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(20L)
        assertTrue("Timed out waiting for workflow state: condition=$condition", condition())
    }

    private fun fixture(name: String, color: Int): File {
        val file = File(root, "$name.jpg")
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
        assertTrue(file.length() > 0L)
        return file
    }
}
