package org.synapseworks.pageharbor.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter

class FilteredPreviewRequestTest {
    @Test
    fun stalePageOrFilterRequestCannotReplaceTheCurrentPreview() {
        val current = FilteredPreviewRequest(2L, "page-b", DocumentFilter.GRAYSCALE)

        assertFalse(FilteredPreviewRequest(1L, "page-a", DocumentFilter.GRAYSCALE).isCurrentFor(current))
        assertFalse(FilteredPreviewRequest(2L, "page-b", DocumentFilter.ORIGINAL).isCurrentFor(current))
        assertFalse(
            FilteredPreviewRequest(
                2L,
                "page-b",
                DocumentFilter.GRAYSCALE,
                DocumentPageRotation.DEGREES_90,
            ).isCurrentFor(current),
        )
        assertTrue(current.isCurrentFor(current))
    }

    @Test
    fun selectorMapsEachVisibleChoiceToItsExpectedDocumentFilter() {
        assertEquals(
            listOf(
                DocumentFilter.ORIGINAL,
                DocumentFilter.AUTO_ENHANCE,
                DocumentFilter.DOCUMENT_CLEANUP,
                DocumentFilter.GRAYSCALE,
                DocumentFilter.BLACK_AND_WHITE,
                DocumentFilter.HIGH_CONTRAST,
                DocumentFilter.SHADOW_GLARE_LOW_V1,
                DocumentFilter.SHADOW_GLARE_BALANCED_V1,
                DocumentFilter.SHADOW_GLARE_STRONG_V1,
                DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,
                DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,
                DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,
                DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,
                DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,
                DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,
                DocumentFilter.BLEED_THROUGH_LOW_V1,
                DocumentFilter.BLEED_THROUGH_BALANCED_V1,
                DocumentFilter.BLEED_THROUGH_STRONG_V1,DocumentFilter.CREASE_STAIN_LOW_V1,DocumentFilter.CREASE_STAIN_BALANCED_V1,DocumentFilter.CREASE_STAIN_STRONG_V1,DocumentFilter.MOIRE_LOW_V1,DocumentFilter.MOIRE_BALANCED_V1,DocumentFilter.MOIRE_STRONG_V1,
            ),
            filterSelectorOptions.map(FilterSelectorOption::filter),
        )
    }

    @Test
    fun originalPreviewDoesNotInvokeTransformation() {
        val source = Any()
        var invoked = false

        val output = applyPreviewTransformationOrOriginal(source, DocumentFilter.ORIGINAL) { _, _ ->
            invoked = true
            Any()
        }

        assertSame(source, output)
        assertFalse(invoked)
    }

    @Test
    fun selectedFilterIsPassedToTheTransformation() {
        val source = "preview"
        var received: DocumentFilter? = null

        val output = applyPreviewTransformationOrOriginal(source, DocumentFilter.HIGH_CONTRAST) { value, filter ->
            received = filter
            "$value-filtered"
        }

        assertEquals("preview-filtered", output)
        assertEquals(DocumentFilter.HIGH_CONTRAST, received)
    }

    @Test
    fun processingFailureFallsBackToTheOriginalPreview() {
        val source = Any()

        val output = applyPreviewTransformationOrOriginal(source, DocumentFilter.BLACK_AND_WHITE) {
                _, _ -> error("processing failed")
            }

        assertSame(source, output)
    }
}
