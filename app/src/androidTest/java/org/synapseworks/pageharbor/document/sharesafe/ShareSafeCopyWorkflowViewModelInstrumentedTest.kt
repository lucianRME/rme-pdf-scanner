package org.synapseworks.pageharbor.document.sharesafe

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.redaction.SecureRedactedPdfPage
import org.synapseworks.pageharbor.document.redaction.secureRedactionSha256
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes

class ShareSafeCopyWorkflowViewModelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application = context.applicationContext as Application
    private lateinit var source: File

    @Before
    fun setUp() {
        source = File(context.cacheDir, "share-safe-workflow-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        source.delete()
        deleteStaleShareSafeFiles(context.cacheDir, Long.MAX_VALUE, 0L)
    }

    @Test
    fun recipeUsesProgressiveStepsAndEveryOutputEditInvalidatesGeneration() {
        val viewModel = ShareSafeCopyWorkflowViewModel(application, SavedStateHandle())
        viewModel.open(input())
        assertTrue(viewModel.state is ShareSafeCopyWorkflowState.ChooseRecipe)
        viewModel.selectRecipe(ShareSafeRecipe.UPLOAD_TO_PORTAL)
        var configuration = viewModel.state as ShareSafeCopyWorkflowState.Configure
        assertEquals(ShareSafeConfigureStep.REDACT, configuration.step)
        assertTrue(configuration.targetSize.enabled)
        assertEquals(TargetSizeBytes.MB_1, configuration.targetSize.maximumBytes)

        val openedGeneration = viewModel.generationForTest()
        viewModel.addRedaction()
        configuration = viewModel.state as ShareSafeCopyWorkflowState.Configure
        assertEquals(1, configuration.redactions.size)
        assertNotEquals(openedGeneration, viewModel.generationForTest())

        viewModel.nextStep()
        assertEquals(
            ShareSafeConfigureStep.PURPOSE,
            (viewModel.state as ShareSafeCopyWorkflowState.Configure).step,
        )
        val beforePurpose = viewModel.generationForTest()
        viewModel.setWatermarkEnabled(true)
        viewModel.updatePurpose("Pentru închiriere בלבד")
        viewModel.setDateEnabled(true)
        viewModel.updateDate("9 Oct 2026")
        viewModel.updatePlacement(ShareSafeWatermarkPlacement.REPEATED)
        viewModel.updateOpacity(ShareSafeWatermarkOpacity.STRONG)
        assertTrue(viewModel.generationForTest() >= beforePurpose + 6L)

        viewModel.nextStep()
        viewModel.updateTargetBytes(TargetSizeBytes.KB_500)
        configuration = viewModel.state as ShareSafeCopyWorkflowState.Configure
        assertEquals(ShareSafeConfigureStep.SIZE, configuration.step)
        assertEquals(TargetSizeBytes.KB_500, configuration.targetSize.maximumBytes)
        viewModel.nextStep()
        assertEquals(
            ShareSafeConfigureStep.REVIEW,
            (viewModel.state as ShareSafeCopyWorkflowState.Configure).step,
        )
        viewModel.close()
    }

    @Test
    fun processDeathRestoresNoUriOrShareableArtifact() {
        val restored = ShareSafeCopyWorkflowViewModel(
            application,
            SavedStateHandle(mapOf("share_safe_workflow_active" to true)),
        )
        val state = restored.state as ShareSafeCopyWorkflowState.Failed
        assertEquals(ShareSafeFailure.PROCESS_INTERRUPTED, state.reason)
        assertFalse(state.canRetry)
        restored.close()
    }

    @Test
    fun pickerCancellationRetainsTheSameRevalidatedPrivateResultForRetry() = runBlocking {
        val viewModel = ShareSafeCopyWorkflowViewModel(application, SavedStateHandle())
        viewModel.open(input())
        viewModel.selectRecipe(ShareSafeRecipe.ARCHIVE_DOCUMENT)
        repeat(3) { viewModel.nextStep() }
        viewModel.prepare()
        waitUntil { viewModel.state is ShareSafeCopyWorkflowState.Prepared }

        val before = viewModel.state as ShareSafeCopyWorkflowState.Prepared
        val finalFile = before.prepared.artifact.file
        assertTrue(viewModel.markSavePickerOpen())
        assertEquals(
            ShareSafeSaveStatus.ChoosingDestination,
            (viewModel.state as ShareSafeCopyWorkflowState.Prepared).saveStatus,
        )
        viewModel.onSaveDestination(null)
        val after = viewModel.state as ShareSafeCopyWorkflowState.Prepared
        assertTrue(after.prepared === before.prepared)
        assertEquals(ShareSafeSaveStatus.Idle, after.saveStatus)
        assertTrue(finalFile.isFile)
        assertTrue(viewModel.verifiedShareUri() != null)

        viewModel.close()
        assertFalse(finalFile.exists())
    }

    @Test
    fun alreadyFitsPrepareCanReturnToSettingsAndPrepareAgain() = runBlocking {
        val viewModel = ShareSafeCopyWorkflowViewModel(application, SavedStateHandle())
        viewModel.open(input())
        viewModel.selectRecipe(ShareSafeRecipe.UPLOAD_TO_PORTAL)
        repeat(3) { viewModel.nextStep() }

        viewModel.prepare()
        waitUntil { viewModel.state is ShareSafeCopyWorkflowState.Prepared }
        val first = (viewModel.state as ShareSafeCopyWorkflowState.Prepared).prepared
        assertTrue(first.bytes <= TargetSizeBytes.MB_1)

        viewModel.back()
        val restored = viewModel.state as ShareSafeCopyWorkflowState.Configure
        assertEquals(ShareSafeConfigureStep.REVIEW, restored.step)
        assertFalse(first.artifact.file.exists())

        viewModel.setTargetSizeEnabled(false)
        viewModel.prepare()
        waitUntil { viewModel.state is ShareSafeCopyWorkflowState.Prepared }
        val second = (viewModel.state as ShareSafeCopyWorkflowState.Prepared).prepared
        assertFalse(second.targetSize.enabled)
        assertTrue(second.artifact.file.isFile)

        viewModel.close()
        assertFalse(second.artifact.file.exists())
    }

    private fun input(): ShareSafeDocumentInput {
        val hash = requireNotNull(secureRedactionSha256(source::inputStream))
        return ShareSafeDocumentInput(
            title = "Synthetic",
            sourceGeneration = 0L,
            pages = listOf(
                ShareSafeDocumentPage(
                    source = SecureRedactedPdfPage(
                        pageId = "page-1",
                        openSource = source::inputStream,
                        imageMetadata = DocumentImageMetadata(source.length(), 640, 900),
                        expectedSourceSha256 = hash,
                        regions = emptyList(),
                    ),
                    previewUri = Uri.fromFile(source),
                ),
            ),
        )
    }

    private suspend fun waitUntil(
        timeoutMillis: Long = 15_000L,
        condition: () -> Boolean,
    ) {
        val started = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - started < timeoutMillis) { "Timed out waiting for state" }
            delay(25L)
        }
    }
}
