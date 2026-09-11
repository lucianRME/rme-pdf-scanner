package org.synapseworks.pageharbor.document.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.image.DocumentFilter

class DocumentSessionTest {
    @Test
    fun reorderPreservesStableIdentitySourceAndPerPageEdits() {
        val first = page(10L, "scan", DocumentSourceCategory.SCAN)
        val second = page(11L, "selected", DocumentSourceCategory.SELECTED_IMAGE).copy(
            filter = DocumentFilter.GRAYSCALE,
            rotation = DocumentPageRotation.DEGREES_90,
        )
        val session = DocumentSession(pages = listOf(first, second))

        val reordered = session.reorder(listOf(second.id, first.id))!!

        assertEquals(listOf(11L, 10L), reordered.pages.map { page -> page.id.value })
        assertEquals("selected", reordered.pages[0].source.reference)
        assertEquals(DocumentSourceCategory.SELECTED_IMAGE, reordered.pages[0].sourceCategory)
        assertEquals(DocumentFilter.GRAYSCALE, reordered.pages[0].filter)
        assertEquals(DocumentPageRotation.DEGREES_90, reordered.pages[0].rotation)
    }

    @Test
    fun invalidReorderRequestsDoNotCreateAMutatedSession() {
        val session = DocumentSession(pages = listOf(page(1L, "one"), page(2L, "two")))

        assertNull(session.reorder(listOf(DocumentPageId(1L))))
        assertNull(session.reorder(listOf(DocumentPageId(1L), DocumentPageId(1L))))
        assertNull(session.reorder(listOf(DocumentPageId(1L), DocumentPageId(99L))))
        assertEquals(listOf(1L, 2L), session.pages.map { page -> page.id.value })
    }

    @Test
    fun rotationCyclesDeterministicallyWithoutChangingIdentity() {
        val original = DocumentSession(pages = listOf(page(7L, "page")))

        val rotated90 = original.rotateClockwise(DocumentPageId(7L))!!
        val rotated180 = rotated90.rotateClockwise(DocumentPageId(7L))!!
        val rotated270 = rotated180.rotateClockwise(DocumentPageId(7L))!!
        val rotated0 = rotated270.rotateClockwise(DocumentPageId(7L))!!

        assertEquals(DocumentPageRotation.DEGREES_90, rotated90.pages.single().rotation)
        assertEquals(DocumentPageRotation.DEGREES_180, rotated180.pages.single().rotation)
        assertEquals(DocumentPageRotation.DEGREES_270, rotated270.pages.single().rotation)
        assertEquals(DocumentPageRotation.DEGREES_0, rotated0.pages.single().rotation)
        assertTrue(listOf(rotated90, rotated180, rotated270, rotated0).all {
            it.pages.single().id == DocumentPageId(7L)
        })
    }

    @Test
    fun directPdfIsAvailableOnlyForItsUneditedOrderedPageSnapshot() {
        val first = page(1L, "one")
        val second = page(2L, "two")
        val original = DocumentSession(
            pages = listOf(first, second),
            directPdfSource = resource("pdf"),
            directPdfPageIds = listOf(first.id, second.id),
        )

        assertTrue(original.canUseDirectPdf)
        assertFalse(original.reorder(listOf(second.id, first.id))!!.canUseDirectPdf)
        assertFalse(original.rotateClockwise(first.id)!!.canUseDirectPdf)
        assertFalse(original.setFilter(second.id, DocumentFilter.HIGH_CONTRAST)!!.canUseDirectPdf)
    }

    private fun page(
        id: Long,
        reference: String,
        category: DocumentSourceCategory = DocumentSourceCategory.SCAN,
    ) = DocumentPage(
        id = DocumentPageId(id),
        source = resource(reference),
        sourceCategory = category,
    )

    private fun resource(reference: String) = DocumentResource(
        reference = reference,
        ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
    )
}
