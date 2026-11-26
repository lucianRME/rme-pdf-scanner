package org.synapseworks.pageharbor.receipt

import org.junit.Assert.*
import org.junit.Test

class ReceiptControllerTest {
    private fun edit(c: ReceiptController): ReceiptState { c.open(); c.manual(); return c.state!! }
    @Test fun correctionReviewAndExplicitRulesPreserveSourceAndManualOverrides() {
        val c=ReceiptController(); c.open(); val s=c.state!!
        assertTrue(c.beginPicker(s.owner,s.epoch)); val t=c.picked(true)!!
        val r=ReceiptParser.parse("Merchant: TEST STORE\nDate: 03/04/2026\nTotal 12.OO")!!; c.complete(t,r)
        var current=c.state!!; assertNull(current.draft.values[ReceiptField.DATE]); assertNull(current.draft.values[ReceiptField.TOTAL])
        c.edit(current.owner,current.epoch,ReceiptField.TOTAL,"13.20")
        c.rules(current.owner,current.epoch,ReceiptRules(dates=ReceiptDateOrder.DMY)); current=c.state!!
        assertEquals("13.20",current.draft.values[ReceiptField.TOTAL]); assertEquals("03/04/2026",current.draft.values[ReceiptField.DATE])
        assertEquals(r.source,current.extraction!!.source)
        c.review(current.owner,current.epoch); assertEquals("2026-04-03",c.state!!.reviewed!![ReceiptField.DATE]); assertEquals("13.20",c.state!!.reviewed!![ReceiptField.TOTAL])
        c.edit(current.owner,current.epoch,ReceiptField.TOTAL,"99.00"); assertEquals("13.20",c.state!!.reviewed!![ReceiptField.TOTAL])
        c.correct(); c.edit(current.owner,current.epoch,ReceiptField.TOTAL,""); c.review(current.owner,current.epoch)
        assertFalse(c.state!!.reviewed!!.containsKey(ReceiptField.TOTAL))
    }
    @Test fun invalidManualFieldsBlockReviewWhileSparseEmptyReceiptIsExplicitlyReviewable() {
        val c=ReceiptController(); val s=edit(c)
        c.edit(s.owner,s.epoch,ReceiptField.TAX,"2.OO"); c.review(s.owner,s.epoch); assertEquals(ReceiptPhase.EDIT,c.state!!.phase); assertNotNull(c.state!!.problem)
        c.edit(s.owner,s.epoch,ReceiptField.TAX,""); c.review(s.owner,s.epoch); assertEquals(emptyMap<ReceiptField,String>(),c.state!!.reviewed)
    }
    @Test fun lockBackgroundAndRecreationNeverPersistOrRepublishStaleContents() {
        val c=ReceiptController(); val s=edit(c); c.edit(s.owner,s.epoch,ReceiptField.MERCHANT,"TEST STORE")
        c.background(true); c.setActive(true); assertEquals("TEST STORE",c.state!!.draft.values[ReceiptField.MERCHANT])
        c.background(false); c.setActive(true); assertTrue(c.state!!.draft.values.isEmpty()); assertNull(c.state!!.extraction)
        c.edit(s.owner,s.epoch,ReceiptField.MERCHANT,"STALE"); assertTrue(c.state!!.draft.values.isEmpty())
        c.lock(); assertNull(c.state); c.open(); assertNull(c.state); c.setActive(true); c.open(); assertNotEquals(s.owner,c.state!!.owner)
    }
    @Test fun stalePickerCannotBeReassignedAndDeferredLockRequiresUnlock() {
        val c=ReceiptController(); c.open(); var s=c.state!!; assertTrue(c.beginPicker(s.owner,s.epoch)); c.close(); c.open(); s=c.state!!
        assertFalse(c.beginPicker(s.owner,s.epoch)); assertNull(c.picked(true)); assertTrue(c.beginPicker(s.owner,s.epoch))
        c.lock(); val t=c.picked(true)!!; assertFalse(c.matches(t,ReceiptPhase.OCR)); c.lock(true); assertNotNull(c.state)
        c.setActive(true); assertTrue(c.matches(t,ReceiptPhase.OCR)); c.complete(t,ReceiptParser.parse("Total 12.00")); assertEquals(ReceiptPhase.EDIT,c.state!!.phase)
        c.lock(); c.setActive(true); c.open(); c.complete(t,ReceiptParser.parse("Total 99.00")); assertNull(c.state!!.extraction)
    }
    @Test fun captureGatesRejectDuplicateAndStaleCompletions() {
        val c=ReceiptController(); c.open(); val s=c.state!!; val t=c.capture(s.owner,s.epoch)!!
        assertNull(c.capture(s.owner,s.epoch)); assertTrue(c.captured(t)); assertFalse(c.captured(t)); c.reset()
        c.complete(t,ReceiptParser.parse("Total 99.00")); assertNull(c.state!!.extraction)
        c.close(); assertFalse(c.captured(t)); c.setActive(false); c.open(); assertNull(c.state)
    }
}
