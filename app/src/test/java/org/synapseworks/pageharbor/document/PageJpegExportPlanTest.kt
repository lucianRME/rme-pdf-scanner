package org.synapseworks.pageharbor.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.ArgbImage
import org.synapseworks.pageharbor.image.DocumentFilter

class PageJpegExportPlanTest {
    @Test
    fun originalUsesTheDirectCopyPlan() {
        assertEquals(
            PageJpegExportPlan.DirectCopy(pageId = 10L),
            pageJpegExportPlan(pageId = 10L, filter = DocumentFilter.ORIGINAL),
        )
    }

    @Test
    fun rotatedOriginalUsesTheSharedTransformationPlan() {
        assertEquals(
            PageJpegExportPlan.Filtered(
                pageId = 10L,
                filter = DocumentFilter.ORIGINAL,
                rotation = DocumentPageRotation.DEGREES_90,
            ),
            pageJpegExportPlan(
                pageId = 10L,
                filter = DocumentFilter.ORIGINAL,
                rotation = DocumentPageRotation.DEGREES_90,
            ),
        )
    }

    @Test
    fun nonJpegOriginalUsesTheSharedTransformationPlan() {
        assertEquals(
            PageJpegExportPlan.Filtered(
                pageId = 11L,
                filter = DocumentFilter.ORIGINAL,
            ),
            pageJpegExportPlan(
                pageId = 11L,
                filter = DocumentFilter.ORIGINAL,
                contentType = "image/png",
            ),
        )
    }

    @Test
    fun grayscaleAndBlackAndWhiteUseTheSharedFilterEngine() {
        val grayscaleSource = image(0xffff0000.toInt())
        val grayscale = applyExportFilter(grayscaleSource, DocumentFilter.GRAYSCALE)
        val blackAndWhite = applyExportFilter(
            image(0xff202020.toInt(), 0xffffffff.toInt()),
            DocumentFilter.BLACK_AND_WHITE,
        )

        assertEquals(54, grayscale.pixels.single() and 0xff)
        assertTrue(blackAndWhite.pixels.all { it and 0x00ffffff == 0 || it and 0x00ffffff == 0x00ffffff })
    }

    @Test
    fun enhanceAndHighContrastUseFilteredFullResolutionPlans() {
        assertEquals(
            PageJpegExportPlan.Filtered(11L, DocumentFilter.AUTO_ENHANCE),
            pageJpegExportPlan(11L, DocumentFilter.AUTO_ENHANCE),
        )
        assertEquals(
            PageJpegExportPlan.Filtered(12L, DocumentFilter.HIGH_CONTRAST),
            pageJpegExportPlan(12L, DocumentFilter.HIGH_CONTRAST),
        )
    }

    @Test
    fun mixedPagesExportInSessionOrderWithTheirOwnFilters() {
        val pages = listOf(
            page(1L, DocumentFilter.ORIGINAL),
            page(2L, DocumentFilter.GRAYSCALE),
            page(3L, DocumentFilter.BLACK_AND_WHITE),
        )

        assertEquals(
            listOf(
                PageJpegExportPlan.DirectCopy(1L),
                PageJpegExportPlan.Filtered(2L, DocumentFilter.GRAYSCALE),
                PageJpegExportPlan.Filtered(3L, DocumentFilter.BLACK_AND_WHITE),
            ),
            pages.map(::pageJpegExportPlan),
        )
    }

    @Test
    fun reorderedPagesKeepTheirFilterWithTheStablePageIdentity() {
        val first = page(1L, DocumentFilter.GRAYSCALE)
        val second = page(2L, DocumentFilter.HIGH_CONTRAST)
        val reordered = listOf(second, first)

        assertEquals(
            listOf(
                PageJpegExportPlan.Filtered(2L, DocumentFilter.HIGH_CONTRAST),
                PageJpegExportPlan.Filtered(1L, DocumentFilter.GRAYSCALE),
            ),
            reordered.map(::pageJpegExportPlan),
        )
    }

    @Test
    fun addedOriginalPageAndRevertedPageUseTheDirectCopyPlan() {
        val reverted = page(1L, DocumentFilter.ORIGINAL)
        val added = page(2L, DocumentFilter.ORIGINAL)

        assertEquals(PageJpegExportPlan.DirectCopy(1L), pageJpegExportPlan(reverted))
        assertEquals(PageJpegExportPlan.DirectCopy(2L), pageJpegExportPlan(added))
    }

    @Test
    fun exportFilteringDoesNotMutateTheSourcePixels() {
        val source = image(0xff606060.toInt(), 0xffa0a0a0.toInt())
        val before = source.pixels.copyOf()

        val output = applyExportFilter(source, DocumentFilter.HIGH_CONTRAST)

        assertArrayEquals(before, source.pixels)
        assertTrue(output !== source)
    }

    @Test
    fun originalFilterReturnsTheSameSourceWithoutProcessing() {
        val source = image(0xff123456.toInt())

        assertSame(source, applyExportFilter(source, DocumentFilter.ORIGINAL))
    }

    @Test
    fun twentyPagesProduceOneOrderedExportPlanPerPage() {
        val pages = (1L..20L).map { page(it, DocumentFilter.ORIGINAL) }

        assertEquals(
            (1L..20L).map(PageJpegExportPlan::DirectCopy),
            pages.map(::pageJpegExportPlan),
        )
    }

    private fun page(id: Long, filter: DocumentFilter): DocumentPage = DocumentPage(
        id = DocumentPageId(id),
        source = DocumentResource(
            reference = "page-$id",
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        ),
        sourceCategory = DocumentSourceCategory.SCAN,
        filter = filter,
    )

    private fun image(vararg pixels: Int): ArgbImage = ArgbImage(pixels.size, 1, pixels)
}
