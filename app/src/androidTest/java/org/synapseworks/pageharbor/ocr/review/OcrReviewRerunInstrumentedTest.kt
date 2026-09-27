package org.synapseworks.pageharbor.ocr.review

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrPageSnapshot
import org.synapseworks.pageharbor.ocr.MultilingualOcrRuntime
import org.synapseworks.pageharbor.ocr.OcrFailureReason
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionEngine
import org.synapseworks.pageharbor.ocr.OcrPageRecognitionOutcome
import org.synapseworks.pageharbor.ocr.OcrScript

class OcrReviewRerunInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun latinDocumentRerunCommitsPagesIncrementallyInIsolatedPackage() = runBlocking {
        assertEquals("org.synapseworks.pageharbor.phase2test", context.packageName)
        val committed = mutableListOf<Pair<String, LibraryOcrArtifactDraft>>()
        val progress = mutableListOf<DocumentOcrRerunProgress>()
        val coordinator = DocumentOcrRerunCoordinator(
            engine = MultilingualOcrRuntime.get(context).recognitionEngine,
            committer = OcrArtifactCommitter { expected, artifact ->
                committed += expected.pageId to artifact
                LibraryOcrCommitResult.APPLIED
            },
        )

        val result = coordinator.run(
            targets = listOf(target(0, "English text"), target(1, "Romanian text")),
            script = OcrScript.LATIN,
            onProgress = progress::add,
        )

        assertEquals(2, result.succeededPages)
        assertEquals(listOf("page-0", "page-1"), committed.map { it.first })
        assertTrue(committed.all { it.second.actualScript == "LATIN" })
        assertEquals(listOf(1, 2), progress.map { it.completedPages })
    }

    @Test
    fun optionalScriptRerunPreservesCorrectionMarkerAndNeverUsesLatinFallback() = runBlocking {
        assertEquals("org.synapseworks.pageharbor.phase2test", context.packageName)
        val runtime = MultilingualOcrRuntime.get(context)
        val install = withTimeout(180_000L) {
            runtime.modelInstaller.requestInstall(OcrScript.JAPANESE)
        }
        assertEquals(OcrModelState.Installed, install)
        var artifact: LibraryOcrArtifactDraft? = null

        val result = DocumentOcrRerunCoordinator(
            engine = runtime.recognitionEngine,
            committer = OcrArtifactCommitter { _, value ->
                artifact = value
                LibraryOcrCommitResult.APPLIED
            },
        ).run(
            targets = listOf(target(0, "日本語テスト", hadCorrection = true)),
            script = OcrScript.JAPANESE,
        )

        assertEquals("JAPANESE", artifact?.actualScript)
        assertEquals("PLAY_SERVICES", artifact?.delivery)
        assertTrue(result.pages.single().correctionPreserved)
    }

    @Test
    fun cancellationStopsBeforeAnyUnfinishedPageCommit() = runBlocking {
        assertEquals("org.synapseworks.pageharbor.phase2test", context.packageName)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var commits = 0
        val coordinator = DocumentOcrRerunCoordinator(
            engine = OcrPageRecognitionEngine { request ->
                started.complete(Unit)
                never.await()
                OcrPageRecognitionOutcome.Failure(request.descriptor, OcrFailureReason.CANCELLED)
            },
            committer = OcrArtifactCommitter { _, _ ->
                commits++
                LibraryOcrCommitResult.APPLIED
            },
        )

        val job = launch { coordinator.run(listOf(target(0, "Cancel")), OcrScript.LATIN) }
        started.await()
        job.cancelAndJoin()

        assertEquals(0, commits)
    }

    private fun target(
        position: Int,
        text: String,
        hadCorrection: Boolean = false,
    ) = DocumentOcrRerunTarget(
        snapshot = LibraryOcrPageSnapshot(
            documentId = "document",
            pageId = "page-$position",
            pagePosition = position,
            documentContentRevision = 1,
            pageVisualRevision = 1,
            ocrStateRevision = 1,
            activeArtifactRevision = 1,
            contentSha256 = "a".repeat(64),
            rotationDegrees = 0,
            filterName = "ORIGINAL",
        ),
        source = pageWithText(text),
        hadCorrection = hadCorrection,
    )

    private fun pageWithText(text: String): OcrPage {
        val bitmap = Bitmap.createBitmap(2200, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                text,
                80f,
                360f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 150f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                },
            )
        }
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        return OcrPage { ByteArrayInputStream(bytes) }
    }
}
