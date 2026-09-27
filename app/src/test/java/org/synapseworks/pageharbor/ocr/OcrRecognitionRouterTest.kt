package org.synapseworks.pageharbor.ocr

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRecognitionRouterTest {
    @Test
    fun everySupportedScriptRoutesOnlyToItsMatchingRecognizer() = runBlocking {
        OcrScript.entries.forEach { script ->
            var acquired: OcrScript? = null
            val router = OcrRecognitionRouter(
                modelStatusProvider = OcrModelStatusProvider {
                    if (script == OcrScript.LATIN) OcrModelState.Bundled else OcrModelState.Installed
                },
                recognizerFactory = OcrRecognizerFactory { requested ->
                    acquired = requested
                    OcrRecognizerAcquisition.Available(FakeRecognizer(requested))
                },
            )

            val result = router.recognize(request(script))

            assertEquals(script, acquired)
            assertEquals(
                script,
                (result as OcrPageRecognitionOutcome.Success).provenance.actualScript,
            )
        }
    }

    @Test
    fun missingOptionalModelReturnsStructuredFailureWithoutAcquiringLatin() = runBlocking {
        var factoryCalled = false
        val router = OcrRecognitionRouter(
            modelStatusProvider = OcrModelStatusProvider { OcrModelState.NotInstalled },
            recognizerFactory = OcrRecognizerFactory {
                factoryCalled = true
                OcrRecognizerAcquisition.Available(FakeRecognizer(OcrScript.LATIN))
            },
        )

        val result = router.recognize(request(OcrScript.JAPANESE))

        assertEquals(OcrFailureReason.MODEL_UNAVAILABLE, result.failureReason())
        assertFalse(factoryCalled)
    }

    @Test
    fun unsupportedAndGooglePlayServicesFailuresRemainDistinct() = runBlocking {
        val unsupported = routerWithState(OcrModelState.Unsupported)
            .recognize(request(OcrScript.KOREAN))
        val unavailable = routerWithState(
            OcrModelState.RetryableFailure(
                OcrModelFailure.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
            ),
        ).recognize(request(OcrScript.CHINESE))

        assertEquals(OcrFailureReason.SCRIPT_UNSUPPORTED, unsupported.failureReason())
        assertEquals(
            OcrFailureReason.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
            unavailable.failureReason(),
        )
    }

    @Test
    fun mismatchedRecognizerIsClosedAndNeverUsedAsFallback() = runBlocking {
        val recognizer = FakeRecognizer(OcrScript.LATIN)
        val router = OcrRecognitionRouter(
            modelStatusProvider = OcrModelStatusProvider { OcrModelState.Installed },
            recognizerFactory = OcrRecognizerFactory {
                OcrRecognizerAcquisition.Available(recognizer)
            },
        )

        val result = router.recognize(request(OcrScript.DEVANAGARI))

        assertEquals(OcrFailureReason.RECOGNITION_FAILED, result.failureReason())
        assertFalse(recognizer.wasInvoked)
        assertTrue(recognizer.wasClosed)
    }

    @Test
    fun recognizerFailureIsReturnedWithoutChangingDescriptor() = runBlocking {
        val expected = request(OcrScript.CHINESE)
        val recognizer = FakeRecognizer(
            script = OcrScript.CHINESE,
            failure = OcrFailureReason.IMAGE_UNREADABLE,
        )
        val router = OcrRecognitionRouter(
            modelStatusProvider = OcrModelStatusProvider { OcrModelState.Installed },
            recognizerFactory = OcrRecognizerFactory {
                OcrRecognizerAcquisition.Available(recognizer)
            },
        )

        val result = router.recognize(expected)

        assertEquals(OcrFailureReason.IMAGE_UNREADABLE, result.failureReason())
        assertEquals(expected.descriptor, result.descriptor)
        assertTrue(recognizer.wasClosed)
    }

    @Test
    fun recognizerCancellationIsReportedTruthfullyAndRecognizerIsClosed() = runBlocking {
        val recognizer = FakeRecognizer(
            script = OcrScript.CHINESE,
            throwsCancellation = true,
        )
        val router = OcrRecognitionRouter(
            modelStatusProvider = OcrModelStatusProvider { OcrModelState.Installed },
            recognizerFactory = OcrRecognizerFactory {
                OcrRecognizerAcquisition.Available(recognizer)
            },
        )

        val outcome = router.recognize(request(OcrScript.CHINESE))

        assertEquals(OcrFailureReason.CANCELLED, outcome.failureReason())
        assertTrue(recognizer.wasClosed)
    }

    private fun routerWithState(state: OcrModelState) = OcrRecognitionRouter(
        modelStatusProvider = OcrModelStatusProvider { state },
        recognizerFactory = OcrRecognizerFactory {
            error("Recognizer must not be acquired for $state")
        },
    )

    private fun request(script: OcrScript) = OcrPageRecognitionRequest(
        descriptor = OcrPageRecognitionDescriptor(
            address = OcrPageAddress("document", "page"),
            capturedPagePosition = 0,
            script = script,
            currentness = OcrRecognitionCurrentness(
                inputFingerprintVersion = 1,
                inputFingerprint = "sha256:synthetic",
                durable = OcrDurablePageCurrentness(
                    documentContentRevision = 1,
                    pageVisualRevision = 2,
                    ocrStateRevision = 3,
                    activeArtifactRevision = null,
                ),
            ),
        ),
        source = OcrPage { ByteArrayInputStream(byteArrayOf(1)) },
    )

    private fun OcrPageRecognitionOutcome.failureReason(): OcrFailureReason =
        (this as OcrPageRecognitionOutcome.Failure).reason

    private class FakeRecognizer(
        override val script: OcrScript,
        private val failure: OcrFailureReason? = null,
        private val throwsCancellation: Boolean = false,
    ) : OcrScriptRecognizer {
        var wasInvoked = false
        var wasClosed = false

        override suspend fun recognize(
            request: OcrPageRecognitionRequest,
        ): OcrPageRecognitionOutcome {
            wasInvoked = true
            if (throwsCancellation) throw CancellationException("synthetic cancellation")
            return failure?.let { reason ->
                OcrPageRecognitionOutcome.Failure(request.descriptor, reason)
            } ?: OcrPageRecognitionOutcome.Success(
                descriptor = request.descriptor,
                rawText = "synthetic",
                layout = null,
                provenance = OcrRecognizerProvenance(
                    actualScript = script,
                    recognizerId = "fake-${script.stableId}",
                    pipelineVersion = "1",
                    delivery = if (script == OcrScript.LATIN) {
                        OcrModelDelivery.BUNDLED
                    } else {
                        OcrModelDelivery.PLAY_SERVICES
                    },
                ),
            )
        }

        override fun close() {
            wasClosed = true
        }
    }
}
