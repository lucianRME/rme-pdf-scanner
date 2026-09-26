package org.synapseworks.pageharbor.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrRecognizerPortsTest {
    @Test
    fun factoryAcquiresRecognizerByStableScript() {
        var requestedScript: OcrScript? = null
        val expected = FakeRecognizer(OcrScript.JAPANESE)
        val factory = OcrRecognizerFactory { script ->
            requestedScript = script
            OcrRecognizerAcquisition.Available(expected)
        }

        val acquisition = factory.acquire(OcrScript.JAPANESE)

        assertEquals(OcrScript.JAPANESE, requestedScript)
        assertSame(
            expected,
            (acquisition as OcrRecognizerAcquisition.Available).recognizer,
        )
    }

    @Test
    fun modelStatusIsQueriedByStableScriptWithoutInstallingIt() = runBlocking {
        var requestedScript: OcrScript? = null
        val statuses = OcrModelStatusProvider { script ->
            requestedScript = script
            OcrModelState.NotInstalled
        }

        assertEquals(OcrModelState.NotInstalled, statuses.stateFor(OcrScript.CHINESE))
        assertEquals(OcrScript.CHINESE, requestedScript)
    }

    private class FakeRecognizer(
        override val script: OcrScript,
    ) : OcrScriptRecognizer {
        override fun recognize(request: OcrPageRecognitionRequest): OcrPageRecognitionOutcome =
            OcrPageRecognitionOutcome.Failure(
                descriptor = request.descriptor,
                reason = OcrFailureReason.RECOGNITION_FAILED,
            )

        override fun close() = Unit
    }
}
