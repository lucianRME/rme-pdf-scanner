package org.synapseworks.pageharbor.document

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.library.duplicate.*

class ManualAdjustmentStateTest {
    private val resource = DocumentResource("synthetic", DocumentResourceOwnership.USER_OR_EXTERNAL)
    private val page = DocumentPage(DocumentPageId(1), resource, DocumentSourceCategory.SCAN)
    @Test fun directPdfFastPathReturnsOnlyAtNeutralAndIdentityIsStable() {
        val session = DocumentSession(listOf(page), resource, directPdfPageIds = listOf(page.id))
        assertTrue(session.canUseDirectPdf)
        val changed = requireNotNull(session.setAdjustments(page.id, ManualImageAdjustments(20, -10)))
        assertFalse(changed.canUseDirectPdf)
        assertEquals(page.id, changed.pages.single().id)
        assertSame(page.source, changed.pages.single().source)
        assertTrue(requireNotNull(changed.setAdjustments(page.id, ManualImageAdjustments())).canUseDirectPdf)
        assertTrue(normalPdfExportPlan(changed) is NormalPdfExportPlan.RecomposeFromPages)
        assertEquals(ManualImageAdjustments(20,-10), (normalPdfExportPlan(changed) as NormalPdfExportPlan.RecomposeFromPages).pages.single().adjustments)
        assertTrue(pageJpegExportPlan(changed.pages.single()) is PageJpegExportPlan.Filtered)
    }
    @Test fun pagesKeepIndependentSettingsAndExistingTransforms() {
        val other = page.copy(id=DocumentPageId(2), filter=DocumentFilter.GRAYSCALE, rotation=DocumentPageRotation.DEGREES_90)
        val session = DocumentSession(listOf(page,other))
        val changed = requireNotNull(session.setAdjustments(page.id, ManualImageAdjustments(30,25)))
        assertEquals(other,changed.pages[1])
        assertEquals(ManualImageAdjustments(),session.pages[0].adjustments)
        assertNull(session.setAdjustments(DocumentPageId(3),ManualImageAdjustments()))
    }
    @Test fun staleApplyIsRejectedAndOnlyEffectiveChangesAdvanceRevision() {
        val model=org.synapseworks.pageharbor.PageHarborSessionViewModel()
        model.installDocumentSessionForTest(DocumentSession(listOf(page)))
        val revision=model.documentRevision
        assertFalse(model.setPageAdjustments(1,ManualImageAdjustments(20),page.copy(filter=DocumentFilter.GRAYSCALE)))
        assertEquals(revision,model.documentRevision)
        assertTrue(model.setPageAdjustments(1,ManualImageAdjustments(20),page))
        assertEquals(revision+1,model.documentRevision)
        assertFalse(model.setPageAdjustments(1,ManualImageAdjustments(20)))
        assertEquals(revision+1,model.documentRevision)
    }
    @Test fun contentFingerprintKeepsLegacyNeutralAndSeparatesVisualEdits() {
        val page = FingerprintPage("1".repeat(64), "image/jpeg", 100, 0, "ORIGINAL")
        val old = DocumentFingerprintV1.calculate(listOf(page))
        assertEquals(1,old.version)
        assertEquals(old,DocumentFingerprintV1.calculate(listOf(page.copy(brightness=0,contrast=0))))
        val adjusted = DocumentFingerprintV1.calculate(listOf(page.copy(brightness=20)))
        assertEquals(2,adjusted.version)
        assertNotEquals(old.sha256,adjusted.sha256)
        assertNotEquals(adjusted.sha256,DocumentFingerprintV1.calculate(listOf(page.copy(contrast=20))).sha256)
    }
}
