package org.synapseworks.pageharbor.document.searchablepdf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrDurablePageCurrentness
import org.synapseworks.pageharbor.ocr.OcrPageAddress
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrRecognitionCurrentness
import org.synapseworks.pageharbor.ocr.OcrResult

class EffectiveOcrPageProviderTest {
    @Test
    fun providerReturnsOneSnapshotBoundEffectivePage() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L)
        val request = EffectiveOcrPageRequest(address(), currentness)
        val expected = rawPage(currentness)
        val provider = EffectiveOcrPageProvider { supplied ->
            assertEquals(request, supplied)
            EffectiveOcrPageProvision.Available(expected)
        }

        assertEquals(EffectiveOcrPageProvision.Available(expected), provider.provide(request))
    }

    @Test
    fun correctedTextMayUseItsPinnedArtifactInsteadOfTheCurrentRawArtifact() {
        val currentness = currentness(activeArtifactRevision = 2L, ocrStateRevision = 4L)
        val page = EffectiveOcrPage(
            address = address(),
            effectiveText = "Corrected synthetic text",
            layout = layout(),
            textSource = EffectiveOcrTextSource.CORRECTED,
            artifactRevision = 1L,
            ocrStateRevision = 4L,
            correctionBaseArtifactRevision = 1L,
            currentness = currentness,
            coordinateSystemVersion = 1,
            transformVersion = 1,
        )

        assertEquals(1L, page.artifactRevision)
        assertEquals(1L, page.correctionBaseArtifactRevision)
        assertEquals(2L, page.currentness.durable?.activeArtifactRevision)
    }

    @Test
    fun rawTextCannotClaimAnArtifactOtherThanTheCapturedActiveArtifact() {
        assertThrows(IllegalArgumentException::class.java) {
            rawPage(currentness(activeArtifactRevision = 3L))
        }
    }

    @Test
    fun correctedTextRequiresItsBaseArtifactRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            EffectiveOcrPage(
                address = address(),
                effectiveText = "Corrected synthetic text",
                layout = layout(),
                textSource = EffectiveOcrTextSource.CORRECTED,
                artifactRevision = 1L,
                ocrStateRevision = 4L,
                currentness = currentness(
                    activeArtifactRevision = 2L,
                    ocrStateRevision = 4L,
                ),
                coordinateSystemVersion = 1,
                transformVersion = 1,
            )
        }
    }

    @Test
    fun resolverUsesCorrectedEffectiveTextAndPositionedLayout() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L, ocrStateRevision = 4L)
        val request = EffectiveOcrPageRequest(address(), currentness)
        val expectedLayout = layout()
        val provider = EffectiveOcrPageProvider {
            EffectiveOcrPageProvision.Available(
                EffectiveOcrPage(
                    address = address(),
                    effectiveText = "Corrected effective text",
                    layout = expectedLayout,
                    textSource = EffectiveOcrTextSource.CORRECTED,
                    artifactRevision = 1L,
                    ocrStateRevision = 4L,
                    correctionBaseArtifactRevision = 1L,
                    currentness = currentness,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                ),
            )
        }

        val resolution = resolveSearchablePdfOcrInput(
            provider = provider,
            pageRequests = listOf(request),
            transientResult = null,
        )

        assertTrue(resolution is SearchablePdfOcrInputResolution.Available)
        val page = (resolution as SearchablePdfOcrInputResolution.Available).result.pages.single()
        assertEquals("Corrected effective text", page.text)
        assertSame(expectedLayout, page.layout)
    }

    @Test
    fun resolverRejectsMismatchedPageIdentityAndExplicitStaleResponse() = runBlocking {
        val currentness = currentness(activeArtifactRevision = 2L)
        val request = EffectiveOcrPageRequest(address(), currentness)
        val mismatchedProvider = EffectiveOcrPageProvider {
            EffectiveOcrPageProvision.Available(
                rawPage(currentness).copy(
                    address = OcrPageAddress("document-1", "page-elsewhere"),
                ),
            )
        }
        val staleCurrentnessProvider = EffectiveOcrPageProvider {
            EffectiveOcrPageProvision.Available(
                rawPage(currentness).copy(
                    currentness = currentness.copy(sessionDocumentRevision = 9L),
                ),
            )
        }

        assertEquals(
            SearchablePdfOcrInputResolution.StaleOrMismatched,
            resolveSearchablePdfOcrInput(mismatchedProvider, listOf(request), null),
        )
        assertEquals(
            SearchablePdfOcrInputResolution.StaleOrMismatched,
            resolveSearchablePdfOcrInput(staleCurrentnessProvider, listOf(request), null),
        )
        assertEquals(
            SearchablePdfOcrInputResolution.StaleOrMismatched,
            resolveSearchablePdfOcrInput(
                provider = EffectiveOcrPageProvider { EffectiveOcrPageProvision.Stale },
                pageRequests = listOf(request),
                transientResult = null,
            ),
        )
    }

    @Test
    fun resolverPreservesLegacyTransientOcrResult() = runBlocking {
        val transient = OcrResult(
            listOf(OcrPageResult(pageIndex = 0, text = "Transient synthetic text")),
        )

        val resolution = resolveSearchablePdfOcrInput(
            provider = null,
            pageRequests = emptyList(),
            transientResult = transient,
        )

        assertTrue(resolution is SearchablePdfOcrInputResolution.Available)
        assertSame(
            transient,
            (resolution as SearchablePdfOcrInputResolution.Available).result,
        )
    }

    private fun rawPage(currentness: OcrRecognitionCurrentness) = EffectiveOcrPage(
        address = address(),
        effectiveText = "Synthetic raw text",
        layout = layout(),
        textSource = EffectiveOcrTextSource.RAW,
        artifactRevision = 2L,
        ocrStateRevision = 3L,
        currentness = currentness,
        coordinateSystemVersion = 1,
        transformVersion = 1,
    )

    private fun currentness(
        activeArtifactRevision: Long,
        ocrStateRevision: Long = 3L,
    ) = OcrRecognitionCurrentness(
        inputFingerprintVersion = 1,
        inputFingerprint = "sha256:synthetic",
        durable = OcrDurablePageCurrentness(
            documentContentRevision = 4L,
            pageVisualRevision = 5L,
            ocrStateRevision = ocrStateRevision,
            activeArtifactRevision = activeArtifactRevision,
        ),
    )

    private fun address() = OcrPageAddress("document-1", "page-1")

    private fun layout() = OcrPageLayout(
        imageWidthPx = 100,
        imageHeightPx = 200,
        lines = emptyList(),
    )
}
