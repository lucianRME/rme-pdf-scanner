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
                DocumentFilter.GRAYSCALE,
                DocumentFilter.BLACK_AND_WHITE,
                DocumentFilter.HIGH_CONTRAST,
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
