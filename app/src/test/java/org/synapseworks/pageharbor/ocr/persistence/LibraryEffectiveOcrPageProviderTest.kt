package org.synapseworks.pageharbor.ocr.persistence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageProvision
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageRequest
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrPageUnavailableReason
import org.synapseworks.pageharbor.document.searchablepdf.EffectiveOcrTextSource
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrLine
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrPage
import org.synapseworks.pageharbor.library.LibraryOcrArtifactVerification
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness

class LibraryEffectiveOcrPageProviderTest {
    @Test
    fun lineAlignedCorrectionBecomesPositionedEffectiveText() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L, ocrStateRevision = 4L)
        val stored = storedPage(
            effectiveText = "Corrected line",
            correctedText = "Corrected line",
            alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
            artifactRevision = 1L,
            activeArtifactRevision = 2L,
            correctionBaseArtifactRevision = 1L,
            ocrStateRevision = 4L,
            lines = listOf(line(text = "Corrected line")),
        )
        val provider = provider(stored)

        val provision = provider.provide(request(currentness))

        assertTrue(provision is EffectiveOcrPageProvision.Available)
        val page = (provision as EffectiveOcrPageProvision.Available).page
        assertEquals("Corrected line", page.effectiveText)
        assertEquals(EffectiveOcrTextSource.CORRECTED, page.textSource)
        assertEquals("Corrected line", page.layout.lines.single().text)
        with(page.layout.lines.single().bounds!!) {
            assertEquals(100f, left)
            assertEquals(400f, top)
            assertEquals(900f, right)
            assertEquals(600f, bottom)
        }
        assertSame(currentness, page.currentness)
    }

    @Test
    fun rawVerifiedArtifactUsesActiveArtifactAndRawPositionedLines() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L)
        val provider = provider(storedPage())

        val provision = provider.provide(request(currentness))

        assertTrue(provision is EffectiveOcrPageProvision.Available)
        val page = (provision as EffectiveOcrPageProvision.Available).page
        assertEquals(EffectiveOcrTextSource.RAW, page.textSource)
        assertEquals(2L, page.artifactRevision)
        assertEquals("Raw line", page.layout.lines.single().text)
    }

    @Test
    fun changedDurableRevisionOrFingerprintIsStale() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L)
        val revisionChanged = provider(storedPage(pageVisualRevision = 7L))
        val fingerprintChanged = provider(storedPage(inputFingerprint = "fingerprint:other"))

        assertEquals(
            EffectiveOcrPageProvision.Stale,
            revisionChanged.provide(request(currentness)),
        )
        assertEquals(
            EffectiveOcrPageProvision.Stale,
            fingerprintChanged.provide(request(currentness)),
        )
    }

    @Test
    fun freeformCorrectionRequiresReconciliationBeforePdfPositioning() = runBlocking {
        val stored = storedPage(
            effectiveText = "Freeform corrected text",
            correctedText = "Freeform corrected text",
            alignment = LibraryOcrCorrectionAlignment.FREEFORM,
            artifactRevision = 1L,
            activeArtifactRevision = 2L,
            correctionBaseArtifactRevision = 1L,
            ocrStateRevision = 4L,
            lines = emptyList(),
        )
        val provider = provider(stored)

        assertEquals(
            EffectiveOcrPageProvision.Unavailable(
                EffectiveOcrPageUnavailableReason.CORRECTION_RECONCILIATION_REQUIRED,
            ),
            provider.provide(
                request(currentness(activeArtifactRevision = 2L, ocrStateRevision = 4L)),
            ),
        )
    }

    @Test
    fun legacyUnverifiedArtifactDoesNotInventPositionedLayout() = runBlocking {
        val provider = provider(
            storedPage(
                verification = LibraryOcrArtifactVerification.LEGACY_UNVERIFIED,
                inputFingerprintVersion = null,
                inputFingerprint = null,
                coordinateSystemVersion = null,
                transformVersion = null,
                uprightWidth = null,
                uprightHeight = null,
                lines = emptyList(),
            ),
        )

        assertEquals(
            EffectiveOcrPageProvision.Unavailable(
                EffectiveOcrPageUnavailableReason.POSITIONED_LAYOUT_NOT_AVAILABLE,
            ),
            provider.provide(request(currentness(activeArtifactRevision = 2L))),
        )
    }

    @Test
    fun providerFailureIsReturnedWithoutLeakingExceptionDetails() = runBlocking {
        val provider = LibraryEffectiveOcrPageProvider { _, _ -> error("sensitive detail") }

        assertEquals(
            EffectiveOcrPageProvision.Unavailable(
                EffectiveOcrPageUnavailableReason.PROVIDER_FAILURE,
            ),
            provider.provide(request(currentness(activeArtifactRevision = 2L))),
        )
    }

    private fun provider(stored: LibraryEffectiveOcrPage) =
        LibraryEffectiveOcrPageProvider { documentId, pageId ->
            assertEquals("document-1", documentId)
            assertEquals("page-1", pageId)
            stored
        }

    private fun request(currentness: OcrRecognitionCurrentness) = EffectiveOcrPageRequest(
        address = OcrPageAddress("document-1", "page-1"),
        expectedCurrentness = currentness,
    )

    private fun currentness(
        activeArtifactRevision: Long,
        ocrStateRevision: Long = 3L,
    ) = OcrRecognitionCurrentness(
        inputFingerprintVersion = 1,
        inputFingerprint = "fingerprint:1",
        durable = OcrDurablePageCurrentness(
            documentContentRevision = 5L,
            pageVisualRevision = 6L,
            ocrStateRevision = ocrStateRevision,
            activeArtifactRevision = activeArtifactRevision,
        ),
    )

    private fun storedPage(
        effectiveText: String = "Raw line",
        correctedText: String? = null,
        alignment: LibraryOcrCorrectionAlignment? = null,
        artifactRevision: Long = 2L,
        activeArtifactRevision: Long = 2L,
        correctionBaseArtifactRevision: Long? = null,
        pageVisualRevision: Long = 6L,
        ocrStateRevision: Long = 3L,
        inputFingerprintVersion: Int? = 1,
        inputFingerprint: String? = "fingerprint:1",
        coordinateSystemVersion: Int? = 1,
        transformVersion: Int? = 1,
        uprightWidth: Int? = 1_000,
        uprightHeight: Int? = 2_000,
        verification: LibraryOcrArtifactVerification =
            LibraryOcrArtifactVerification.CURRENT_VERIFIED,
        lines: List<LibraryEffectiveOcrLine> = listOf(line()),
    ) = LibraryEffectiveOcrPage(
        documentId = "document-1",
        pageId = "page-1",
        pagePosition = 0,
        effectiveText = effectiveText,
        rawText = "Raw line",
        correctedText = correctedText,
        alignment = alignment,
        artifactRevision = artifactRevision,
        activeArtifactRevision = activeArtifactRevision,
        correctionBaseArtifactRevision = correctionBaseArtifactRevision,
        documentContentRevision = 5L,
        pageVisualRevision = pageVisualRevision,
        ocrStateRevision = ocrStateRevision,
        inputFingerprintVersion = inputFingerprintVersion,
        inputFingerprint = inputFingerprint,
        coordinateSystemVersion = coordinateSystemVersion,
        transformVersion = transformVersion,
        uprightWidth = uprightWidth,
        uprightHeight = uprightHeight,
        verification = verification,
        actualScript = "LATIN",
        recognizerId = "rme-mlkit-latin",
        lines = lines,
    )

    private fun line(text: String = "Raw line") = LibraryEffectiveOcrLine(
        lineOrdinal = 0,
        text = text,
        topLeftX = 0.1,
        topLeftY = 0.2,
        topRightX = 0.9,
        topRightY = 0.2,
        bottomRightX = 0.9,
        bottomRightY = 0.3,
        bottomLeftX = 0.1,
        bottomLeftY = 0.3,
        baselineStartX = 0.1,
        baselineStartY = 0.28,
        baselineEndX = 0.9,
        baselineEndY = 0.28,
        baselineAngleDegrees = 0.0,
        writingOrientation = null,
    )
}
