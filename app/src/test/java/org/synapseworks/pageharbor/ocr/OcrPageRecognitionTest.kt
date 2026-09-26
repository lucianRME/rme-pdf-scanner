package org.synapseworks.pageharbor.ocr

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPageRecognitionTest {
    @Test
    fun descriptorContainsOnlyReconstructableAddressSelectionAndCurrentness() {
        val descriptor = descriptor()

        assertEquals("document-1", descriptor.address.documentId)
        assertEquals("page-2", descriptor.address.pageId)
        assertEquals(3, descriptor.capturedPagePosition)
        assertEquals(OcrScript.DEVANAGARI, descriptor.script)
        assertEquals(1, descriptor.currentness.inputFingerprintVersion)
        assertEquals(8L, descriptor.currentness.durable?.ocrStateRevision)
    }

    @Test
    fun requestKeepsTheLocalSourceSeparateFromReconstructableMetadata() {
        val request = OcrPageRecognitionRequest(
            descriptor = descriptor(),
            source = OcrPage { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

        assertEquals(3, request.source.openJpegStream().use { it.readBytes().size })
        assertEquals("page-2", request.descriptor.address.pageId)
    }

    @Test
    fun exactAddressAndRevisionSnapshotAcceptsOutcomeWhileAnyMutationRejectsIt() {
        val descriptor = descriptor()
        val outcome = OcrPageRecognitionOutcome.Success(
            descriptor = descriptor,
            rawText = "",
            layout = null,
            provenance = OcrRecognizerProvenance(
                actualScript = OcrScript.DEVANAGARI,
                recognizerId = "rme-mlkit-devanagari",
                pipelineVersion = "1",
                delivery = OcrModelDelivery.PLAY_SERVICES,
            ),
        )

        assertTrue(outcome.isCurrentFor(descriptor.address, descriptor.currentness))
        assertFalse(
            outcome.isCurrentFor(
                descriptor.address,
                descriptor.currentness.copy(
                    durable = descriptor.currentness.durable?.copy(pageVisualRevision = 5L),
                ),
            ),
        )
        assertFalse(
            outcome.isCurrentFor(
                OcrPageAddress("document-1", "page-elsewhere"),
                descriptor.currentness,
            ),
        )
    }

    @Test
    fun durableOnlyDescriptorSupportsRepositoryReconstructionAfterProcessLoss() {
        val reconstructed = descriptor().copy(
            currentness = descriptor().currentness.copy(sessionDocumentRevision = null),
        )

        assertEquals(null, reconstructed.currentness.sessionDocumentRevision)
        assertEquals(7L, reconstructed.currentness.durable?.pageVisualRevision)
    }

    @Test
    fun invalidCurrentnessAndIdentityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrPageAddress("", "page")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrRecognitionCurrentness(
                inputFingerprintVersion = 1,
                inputFingerprint = "fingerprint",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrDurablePageCurrentness(0L, 0L, 0L, activeArtifactRevision = 0L)
        }
    }

    private fun descriptor() = OcrPageRecognitionDescriptor(
        address = OcrPageAddress(documentId = "document-1", pageId = "page-2"),
        capturedPagePosition = 3,
        script = OcrScript.DEVANAGARI,
        currentness = OcrRecognitionCurrentness(
            inputFingerprintVersion = 1,
            inputFingerprint = "sha256:synthetic",
            sessionDocumentRevision = 4L,
            documentPageOrderFingerprint = "order:synthetic",
            durable = OcrDurablePageCurrentness(
                documentContentRevision = 6L,
                pageVisualRevision = 7L,
                ocrStateRevision = 8L,
                activeArtifactRevision = 9L,
            ),
        ),
    )
}
