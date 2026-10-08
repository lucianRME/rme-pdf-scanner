package org.synapseworks.pageharbor.document.targetsize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory

class TargetSizeWorkflowViewModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        testRoot = File(context.cacheDir, "target-size-workflow-test-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
        File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).listFiles()?.forEach(File::deleteRecursively)
    }

    @Test
    fun cancellationReturnsToEditableSettingsWithoutPublishingAStaleResult() {
        val viewModel = viewModel()
        val input = input(pageCount = 20)
        viewModel.open(input)
        viewModel.selectPreset(TargetSizePreset.MB_2)

        viewModel.prepare()
        assertTrue(viewModel.state is TargetSizeWorkflowState.Preparing)
        viewModel.cancelPreparation()

        val restored = viewModel.state
        assertTrue(restored is TargetSizeWorkflowState.ChooseLimit)
        assertEquals(
            TargetSizeLimitSelection.Preset(TargetSizePreset.MB_2),
            (restored as TargetSizeWorkflowState.ChooseLimit).selection,
        )
        Thread.sleep(300L)
        instrumentation.waitForIdleSync()
        assertTrue(viewModel.state is TargetSizeWorkflowState.ChooseLimit)
        viewModel.close()
    }

    @Test
    fun pickerCancellationKeepsPreparedResultAndDestinationRetrySavesVerifiedCopy() {
        val viewModel = viewModel()
        viewModel.open(input(pageCount = 1))
        viewModel.selectPreset(TargetSizePreset.MB_2)
        viewModel.prepare()
        val preparedState = awaitPrepared(viewModel)
        val prepared = preparedState.prepared

        viewModel.markSavePickerOpen()
        assertEquals(
            TargetSizeSaveStatus.ChoosingDestination,
            (viewModel.state as TargetSizeWorkflowState.Prepared).saveStatus,
        )
        viewModel.onSaveDestination(null)
        val afterCancel = viewModel.state as TargetSizeWorkflowState.Prepared
        assertSame(prepared, afterCancel.prepared)
        assertEquals(TargetSizeSaveStatus.Idle, afterCancel.saveStatus)
        assertTrue(prepared.file.isFile)

        val destination = File(
            File(context.cacheDir, TARGET_SIZE_DIRECTORY_NAME).apply { mkdirs() },
            "workflow-destination-${System.nanoTime()}.pdf",
        ).apply { createNewFile() }
        val destinationUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destination,
        )
        viewModel.markSavePickerOpen()
        viewModel.onSaveDestination(destinationUri)
        val saved = awaitPrepared(viewModel) { it.saveStatus is TargetSizeSaveStatus.Saved }

        assertEquals(TargetSizeSaveStatus.Saved(prepared.finalBytes), saved.saveStatus)
        assertTrue(prepared.file.readBytes().contentEquals(destination.readBytes()))
        assertTrue(prepared.file.isFile)

        viewModel.changeLimit()
        assertTrue(viewModel.state is TargetSizeWorkflowState.ChooseLimit)
        assertFalse(prepared.file.exists())
        viewModel.close()
    }

    @Test
    fun restoredActiveMarkerFailsCleanlyWithoutPersistingDocumentState() {
        val handle = SavedStateHandle(mapOf("target_size_workflow_active" to true))
        val viewModel = viewModel(handle)

        val state = viewModel.state
        assertTrue(state is TargetSizeWorkflowState.Failed)
        assertEquals(
            TargetSizePdfFailure.PROCESS_INTERRUPTED,
            (state as TargetSizeWorkflowState.Failed).reason,
        )
        assertEquals(null, state.input)

        viewModel.close()
        assertEquals(false, handle.get<Boolean>("target_size_workflow_active"))
        assertEquals(TargetSizeWorkflowState.Hidden, viewModel.state)
    }

    @Test
    fun representativeUiToResultRunsStayBoundedAndCleanUp() {
        listOf(8 to TargetSizePreset.MB_1, 20 to TargetSizePreset.MB_2).forEach { (count, preset) ->
            val viewModel = viewModel()
            viewModel.open(input(pageCount = count))
            viewModel.selectPreset(preset)
            val started = android.os.SystemClock.elapsedRealtime()
            viewModel.prepare()
            val prepared = awaitPrepared(viewModel).prepared
            val duration = android.os.SystemClock.elapsedRealtime() - started
            val workingDirectory = prepared.workingDirectory

            Log.i(
                "RME_M5_METRICS",
                "pages=$count target_bytes=${preset.bytes} ui_to_result_ms=$duration " +
                    "final_bytes=${prepared.finalBytes} " +
                    "passes=${prepared.performance.fullDocumentPassCount} " +
                    "temp_peak_bytes=${prepared.performance.peakTemporaryBytes}",
            )
            assertTrue(prepared.finalBytes <= preset.bytes)
            assertTrue(prepared.performance.fullDocumentPassCount <= 2)

            viewModel.close()
            assertFalse(workingDirectory.exists())
        }
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): TargetSizeWorkflowViewModel =
        TargetSizeWorkflowViewModel(context.applicationContext as Application, handle)

    private fun input(pageCount: Int): TargetSizeWorkflowInput {
        val source = createJpeg()
        val pages = List(pageCount) { index ->
            TargetSizePdfPage(
                pageId = index.toLong(),
                sourceUri = Uri.fromFile(source),
                sourceCategory = DocumentSourceCategory.SCAN,
                contentType = "image/jpeg",
                imageMetadata = DocumentImageMetadata(source.length(), 600, 800),
                contentProfile = TargetSizePageContentProfile.TEXT_HEAVY,
            )
        }
        return TargetSizeWorkflowInput(
            summary = TargetSizeSourceSummary(
                title = "Workflow test",
                pageCount = pageCount,
                sourceBytes = source.length() * pageCount,
            ),
            request = TargetSizePdfRequest(pages, targetBytes = TargetSizeBytes.MB_1),
        )
    }

    private fun createJpeg(): File {
        val output = File(testRoot, "page.jpg")
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 24f
            }
            repeat(24) { line ->
                canvas.drawText("Workflow test line $line", 30f, 40f + line * 28f, paint)
            }
            FileOutputStream(output).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream))
                stream.flush()
                stream.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        return output
    }

    private fun awaitPrepared(
        viewModel: TargetSizeWorkflowViewModel,
        predicate: (TargetSizeWorkflowState.Prepared) -> Boolean = { true },
    ): TargetSizeWorkflowState.Prepared {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val state = viewModel.state
            if (state is TargetSizeWorkflowState.Prepared && predicate(state)) return state
            if (state is TargetSizeWorkflowState.Failed) {
                throw AssertionError("Workflow failed: ${state.reason}")
            }
            Thread.sleep(25L)
        }
        throw AssertionError("Timed out waiting for prepared state: ${viewModel.state}")
    }
}
