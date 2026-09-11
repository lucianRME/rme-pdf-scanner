package org.synapseworks.pageharbor.document.searchablepdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.image.DocumentFilter

class SearchablePdfVisualPlanTest {
    @Test
    fun originalRetainsTheDirectVisualSourcePlan() {
        assertEquals(
            SearchablePdfVisualPlan.Original(pageId = 1L),
            searchablePdfVisualPlan(1L, DocumentFilter.ORIGINAL),
        )
    }

    @Test
    fun grayscaleAndBlackAndWhiteUseTheirOwnFilteredVisualPlans() {
        assertEquals(
            SearchablePdfVisualPlan.Filtered(2L, DocumentFilter.GRAYSCALE),
            searchablePdfVisualPlan(2L, DocumentFilter.GRAYSCALE),
        )
        assertEquals(
            SearchablePdfVisualPlan.Filtered(3L, DocumentFilter.BLACK_AND_WHITE),
            searchablePdfVisualPlan(3L, DocumentFilter.BLACK_AND_WHITE),
        )
    }

    @Test
    fun enhanceAndHighContrastUseTheirOwnFilteredVisualPlans() {
        assertEquals(
            SearchablePdfVisualPlan.Filtered(4L, DocumentFilter.AUTO_ENHANCE),
            searchablePdfVisualPlan(4L, DocumentFilter.AUTO_ENHANCE),
        )
        assertEquals(
            SearchablePdfVisualPlan.Filtered(5L, DocumentFilter.HIGH_CONTRAST),
            searchablePdfVisualPlan(5L, DocumentFilter.HIGH_CONTRAST),
        )
    }

    @Test
    fun rotatedOriginalUsesAVisualTransformationWithoutChangingTheOcrSource() {
        val plan = searchablePdfVisualPlan(
            pageId = 6L,
            filter = DocumentFilter.ORIGINAL,
            rotation = DocumentPageRotation.DEGREES_90,
        )

        assertEquals(
            SearchablePdfVisualPlan.Filtered(
                pageId = 6L,
                filter = DocumentFilter.ORIGINAL,
                rotation = DocumentPageRotation.DEGREES_90,
            ),
            plan,
        )
        assertEquals(SearchablePdfOcrSource.ORIGINAL, plan.ocrSource)
    }

    @Test
    fun nonJpegOriginalUsesTheSharedJpegVisualTransformation() {
        assertEquals(
            SearchablePdfVisualPlan.Filtered(
                pageId = 7L,
                filter = DocumentFilter.ORIGINAL,
            ),
            searchablePdfVisualPlan(
                pageId = 7L,
                filter = DocumentFilter.ORIGINAL,
                contentType = "image/png",
            ),
        )
    }

    @Test
    fun mixedAndReorderedPagesKeepTheirVisualFiltersWithTheirIdentities() {
        val reordered = listOf(
            3L to DocumentFilter.BLACK_AND_WHITE,
            1L to DocumentFilter.ORIGINAL,
            2L to DocumentFilter.GRAYSCALE,
        )

        assertEquals(
            listOf(
                SearchablePdfVisualPlan.Filtered(3L, DocumentFilter.BLACK_AND_WHITE),
                SearchablePdfVisualPlan.Original(1L),
                SearchablePdfVisualPlan.Filtered(2L, DocumentFilter.GRAYSCALE),
            ),
            reordered.map { (pageId, filter) -> searchablePdfVisualPlan(pageId, filter) },
        )
    }

    @Test
    fun visualFilterChoiceNeverChangesTheOriginalOcrSourceContract() {
        DocumentFilter.entries.forEach { filter ->
            assertEquals(
                SearchablePdfOcrSource.ORIGINAL,
                searchablePdfVisualPlan(7L, filter).ocrSource,
            )
        }
    }

    @Test
    fun identicalPageAndFilterProduceTheSameVisualPlan() {
        val first = searchablePdfVisualPlan(8L, DocumentFilter.HIGH_CONTRAST)
        val second = searchablePdfVisualPlan(8L, DocumentFilter.HIGH_CONTRAST)

        assertEquals(first, second)
        assertTrue(first is SearchablePdfVisualPlan.Filtered)
    }

    @Test
    fun twentyPageDocumentRetainsEveryOrderedOcrSourceAndVisualPlan() {
        val pages = (1L..20L).map { pageId ->
            pageId to if (pageId == 11L) DocumentFilter.HIGH_CONTRAST else DocumentFilter.ORIGINAL
        }

        val plans = pages.map { (pageId, filter) -> searchablePdfVisualPlan(pageId, filter) }

        assertEquals((1L..20L).toList(), plans.map(SearchablePdfVisualPlan::pageId))
        assertEquals(SearchablePdfOcrSource.ORIGINAL, plans[10].ocrSource)
        assertEquals(SearchablePdfVisualPlan.Filtered(11L, DocumentFilter.HIGH_CONTRAST), plans[10])
    }
}
