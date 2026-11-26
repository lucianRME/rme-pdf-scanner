package org.synapseworks.pageharbor.businesscard

import org.junit.Assert.*
import org.junit.Test

class CardControllerTest {
    private fun ready(c: CardController): CardToken {
        c.open(); val s = c.state!!; val t = c.capture(s.owner, s.epoch)!!; assertTrue(c.captured(t))
        c.complete(t, ContactSuggestionsParser.parse("Name: Synthetic Person\nPhone: +1 202 555 0100")); return t
    }
    @Test fun reviewIsRequiredAndRapidHandoffOnlyAdmitsOneSnapshot() {
        val c = CardController(); val t = ready(c)
        assertNull(c.handoff(t.owner, t.epoch)); c.edit(t.owner, t.epoch) { it.copy(name = "Corrected Person") }
        c.review(t.owner, t.epoch); assertEquals("Corrected Person", c.handoff(t.owner, t.epoch)!!.name)
        assertNull(c.handoff(t.owner, t.epoch)); assertNull(c.state!!.suggestions); assertEquals("", c.state!!.draft.name)
        c.editorReturned(); assertEquals(CardPhase.RETURNED, c.state!!.phase)
        assertFalse(c.state!!.problem!!.contains("saved successfully"))
    }
    @Test fun staleCaptureOcrAndReviewCannotPublishOrLaunchAfterResetOrExit() {
        val c = CardController(); c.open(); val s = c.state!!; val old = c.capture(s.owner, s.epoch)!!
        c.reset(); assertFalse(c.captured(old)); c.complete(old, ContactSuggestionsParser.parse("Name: Old Person"))
        assertNull(c.state!!.suggestions); assertNull(c.handoff(s.owner, s.epoch))
        c.close(); c.open(); c.review(s.owner, s.epoch); assertNull(c.handoff(s.owner, s.epoch))
    }
    @Test fun recreationRetainsDraftButBackgroundAndLockClearSensitiveState() {
        val c = CardController(); ready(c); c.background(true); assertEquals("Synthetic Person", c.state!!.draft.name)
        c.setActive(true); c.background(false); assertEquals(CardPhase.PAUSED, c.state!!.phase); assertNull(c.state!!.suggestions)
        c.setActive(true); c.reset(); val s = c.state!!; c.capture(s.owner, s.epoch); c.lock(); assertNull(c.state)
    }
    @Test fun oldPickerAndEditorReservationsCannotBeAssignedToANewScan() {
        val c = CardController(); c.open(); val s = c.state!!; assertTrue(c.beginPicker(s.owner, s.epoch))
        c.close(); c.open(); val next = c.state!!; assertFalse(c.beginPicker(next.owner, next.epoch)); assertNull(c.picked(true))
        assertTrue(c.beginPicker(next.owner, next.epoch)); val token = c.picked(true)!!
        c.complete(token, ContactSuggestionsParser.parse("Name: New Person")); c.review(token.owner, token.epoch); assertNotNull(c.handoff(token.owner, token.epoch))
        c.close(); c.open(); c.editorReturned(); assertEquals(CardPhase.CAMERA, c.state!!.phase)
    }
    @Test fun invalidFieldsAndInactiveStateCannotReachHandoff() {
        val c = CardController(); val t = ready(c); c.edit(t.owner, t.epoch) { it.copy(name = "bad\u202E") }; c.review(t.owner, t.epoch)
        assertEquals(CardPhase.EDIT, c.state!!.phase); assertNotNull(c.state!!.problem)
        c.edit(t.owner, t.epoch) { it.copy(name = "Good Person") }; c.review(t.owner, t.epoch); c.setActive(false)
        assertNull(c.handoff(t.owner, t.epoch)); c.setActive(true); c.editorFailed()
    }
}
