package org.synapseworks.pageharbor.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter

class NormalPdfExportPlanTest {
    @Test
    fun pristineScannerPagesUseTheDirectScannerPdfPlan() {
        val session = session(listOf(page(1L), page(2L)))

        assertEquals(
            NormalPdfExportPlan.DirectScannerPdf(source = session.directPdfSource),
            normalPdfExportPlan(session),
        )
    }

    @Test
    fun originalPagesWithoutADirectPdfUseTheSharedRecompositionPath() {
        val session = session(listOf(page(1L), page(2L)), hasDirectPdf = false)

        assertTrue(canExportNormalPdf(session))
        assertTrue(normalPdfExportPlan(session) is NormalPdfExportPlan.RecomposeFromPages)
    }

    @Test
    fun emptySessionCannotExposeNormalPdfSaveOrShare() {
        assertEquals(false, canExportNormalPdf(DocumentSession()))
    }

    @Test
    fun oneFilteredPageSelectsRecomposition() {
        val session = session(listOf(page(1L, DocumentFilter.GRAYSCALE)))

        assertEquals(
            NormalPdfExportPlan.RecomposeFromPages(
                listOf(NormalPdfPage(1L, session.pages.single().source, DocumentFilter.GRAYSCALE)),
            ),
            normalPdfExportPlan(session),
        )
    }

    @Test
    fun mixedPagesRecomposeInTheirEffectiveOrderWithTheirOwnEdits() {
        val pages = listOf(
            page(3L, DocumentFilter.BLACK_AND_WHITE),
            page(1L),
            page(2L, DocumentFilter.GRAYSCALE, DocumentPageRotation.DEGREES_90),
        )
        val session = session(pages, directPdfPageIds = listOf(1L, 2L, 3L))

        assertEquals(
            NormalPdfExportPlan.RecomposeFromPages(
                listOf(
                    NormalPdfPage(3L, pages[0].source, DocumentFilter.BLACK_AND_WHITE),
                    NormalPdfPage(1L, pages[1].source, DocumentFilter.ORIGINAL),
                    NormalPdfPage(
                        2L,
                        pages[2].source,
                        DocumentFilter.GRAYSCALE,
                        DocumentPageRotation.DEGREES_90,
                    ),
                ),
            ),
            normalPdfExportPlan(session),
        )
    }

    @Test
    fun revertingTheLastEditReturnsToTheDirectPath() {
        val original = session(listOf(page(1L)))
        val filtered = original.setFilter(DocumentPageId(1L), DocumentFilter.HIGH_CONTRAST)!!

        assertTrue(normalPdfExportPlan(filtered) is NormalPdfExportPlan.RecomposeFromPages)
        assertEquals(
            NormalPdfExportPlan.DirectScannerPdf(original.directPdfSource),
            normalPdfExportPlan(filtered.setFilter(DocumentPageId(1L), DocumentFilter.ORIGINAL)!!),
        )
    }

    @Test
    fun appendedOriginalPageInvalidatesThePartialScannerPdf() {
        val pages = listOf(page(1L), page(2L))
        val session = session(pages, directPdfPageIds = listOf(1L))

        assertTrue(normalPdfExportPlan(session) is NormalPdfExportPlan.RecomposeFromPages)
    }

    @Test
    fun saveAndShareReceiveTheSamePlanForTheSameActiveSession() {
        val session = session(listOf(page(1L), page(2L, DocumentFilter.HIGH_CONTRAST)))

        assertEquals(normalPdfExportPlan(session), normalPdfExportPlan(session))
    }

    @Test
    fun planningDoesNotMutateTheSessionPages() {
        val session = session(listOf(page(1L, DocumentFilter.GRAYSCALE), page(2L)))
        val before = session.pages.toList()

        normalPdfExportPlan(session)

        assertEquals(before, session.pages)
    }

    @Test
    fun twentyPageMixedDocumentRecomposesEveryPageInSessionOrder() {
        val pages = (1L..20L).map { id ->
            page(id, if (id == 11L) DocumentFilter.GRAYSCALE else DocumentFilter.ORIGINAL)
        }

        val plan = normalPdfExportPlan(session(pages)) as NormalPdfExportPlan.RecomposeFromPages

        assertEquals((1L..20L).toList(), plan.pages.map(NormalPdfPage::pageId))
        assertEquals(DocumentFilter.GRAYSCALE, plan.pages[10].filter)
    }

    private fun session(
        pages: List<DocumentPage>,
        hasDirectPdf: Boolean = true,
        directPdfPageIds: List<Long> = pages.map { page -> page.id.value },
    ) = DocumentSession(
        pages = pages,
        directPdfSource = if (hasDirectPdf) resource("scanner-pdf") else null,
        directPdfPageIds = if (hasDirectPdf) directPdfPageIds.map(::DocumentPageId) else emptyList(),
    )

    private fun page(
        id: Long,
        filter: DocumentFilter = DocumentFilter.ORIGINAL,
        rotation: DocumentPageRotation = DocumentPageRotation.DEGREES_0,
    ) = DocumentPage(
        id = DocumentPageId(id),
        source = resource("page-$id"),
        sourceCategory = DocumentSourceCategory.SCAN,
        filter = filter,
        rotation = rotation,
    )

    private fun resource(reference: String) = DocumentResource(
        reference = reference,
        ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
    )
}
