package org.synapseworks.pageharbor.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualOcrRuntimeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runtimeUsesOnlyIsolatedTargetAndKeepsLatinBundled() = runBlocking {
        assertEquals("org.synapseworks.pageharbor.phase2test", context.packageName)
        val first = MultilingualOcrRuntime.get(context)
        val second = MultilingualOcrRuntime.get(context)

        assertTrue(first === second)
        assertEquals(OcrModelState.Bundled, first.modelInstaller.stateFor(OcrScript.LATIN))
    }

    @Test
    fun optionalModelsInstallAndRouteThroughTheirExactRecognizers() = runBlocking {
        assertEquals("org.synapseworks.pageharbor.phase2test", context.packageName)
        val runtime = MultilingualOcrRuntime.get(context)
        val fixtures = listOf(
            OcrScript.CHINESE to "中文测试",
            OcrScript.JAPANESE to "日本語テスト",
            OcrScript.KOREAN to "한국어 테스트",
            OcrScript.DEVANAGARI to "हिन्दी परीक्षण",
        )

        fixtures.forEach { (script, fixture) ->
            val installState = withTimeout(180_000L) {
                runtime.modelInstaller.requestInstall(script)
            }
            assertEquals("$script model installation failed: $installState", OcrModelState.Installed, installState)
            assertEquals(OcrModelState.Installed, runtime.modelInstaller.stateFor(script))

            val outcome = runtime.recognitionEngine.recognize(request(script, fixture))
            assertTrue("$script recognition failed: $outcome", outcome is OcrPageRecognitionOutcome.Success)
            outcome as OcrPageRecognitionOutcome.Success
            assertEquals(script, outcome.provenance.actualScript)
            assertEquals(OcrModelDelivery.PLAY_SERVICES, outcome.provenance.delivery)
            assertTrue("$script returned no text", outcome.rawText.isNotBlank())
        }
    }

    private fun request(script: OcrScript, fixture: String) = OcrPageRecognitionRequest(
        descriptor = OcrPageRecognitionDescriptor(
            address = OcrPageAddress("synthetic-document", "synthetic-${script.stableId}"),
            capturedPagePosition = 0,
            script = script,
            currentness = OcrRecognitionCurrentness(
                inputFingerprintVersion = 1,
                inputFingerprint = "synthetic-${script.stableId}",
                durable = OcrDurablePageCurrentness(
                    documentContentRevision = 0,
                    pageVisualRevision = 0,
                    ocrStateRevision = 0,
                    activeArtifactRevision = null,
                ),
            ),
        ),
        source = pageWithText(fixture),
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
