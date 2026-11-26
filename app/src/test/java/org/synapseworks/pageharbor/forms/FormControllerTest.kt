package org.synapseworks.pageharbor.forms

import org.junit.Test
import org.junit.Assert.*

class FormControllerTest {
    private val model=FormModel(listOf(FormField("a","Name",FormKind.TEXT,listOf("0012"),false,false,listOf(1,2)),FormField("r","Reference",FormKind.TEXT,listOf("KEEP"),true,false,listOf(1))),2)
    private fun loaded():FormController=FormController().apply{open();val s=state!!;assertTrue(beginInput(s.owner,s.epoch));loaded(picked(true)!!,model)}
    private fun review(c:FormController):FormToken {var s=c.state!!;c.review(s.owner,s.epoch);s=c.state!!;c.confirm(s.owner,s.epoch,s.review!!.id,true);return c.prepare(s.owner,s.epoch,s.review.id)!!}
    private fun ready():FormController=loaded().apply{prepared(review(this),null)}
    @Test fun sourceAndReviewIdentityRejectStaleLoadsAndEdits(){val c=loaded();val old=c.state!!;c.beginInput(old.owner,old.epoch);val t=c.picked(true)!!;c.close();c.open();c.loaded(t,model);assertNull(c.state!!.model);c.edit(old.owner,old.epoch,"a",listOf("stale"));assertTrue(c.state!!.values.isEmpty())}
    @Test fun reviewRequiresValidValuesAndExplicitConfirmation(){val c=loaded();val s=c.state!!;c.edit(s.owner,s.epoch,"a",listOf("😀"));c.review(s.owner,s.epoch);assertEquals(FormPhase.EDIT,c.state!!.phase);c.edit(s.owner,s.epoch,"a",listOf("0010"));c.edit(s.owner,s.epoch,"r",listOf("bad"));c.review(s.owner,s.epoch);val r=c.state!!;assertEquals(listOf("KEEP"),r.values["r"]);assertNull(c.prepare(r.owner,r.epoch,r.review!!.id));assertEquals(listOf("0010"),r.review.values["a"])}
    @Test fun correctingInvalidatesPreparedCopyAndDestinationCallback(){val c=ready();val s=c.state!!;assertTrue(c.beginSave(s.owner,s.epoch,s.review!!.id));c.cancelWork();c.correct();c.edit(c.state!!.owner,c.state!!.epoch,"a",listOf("new"));assertNull(c.destination(true));assertEquals(FormPhase.EDIT,c.state!!.phase);assertNull(c.state!!.review)}
    @Test fun outstandingDestinationCannotBeReassignedToAnotherReview(){val c=ready();var s=c.state!!;c.beginSave(s.owner,s.epoch,s.review!!.id);c.cancelWork();c.correct();c.prepared(review(c),null);s=c.state!!;assertFalse(c.beginSave(s.owner,s.epoch,s.review!!.id));assertNull(c.destination(true));assertTrue(c.beginSave(s.owner,s.epoch,s.review.id))}
    @Test fun cancellationAndDuplicateCallbacksNeverStartWrites(){val c=ready();val s=c.state!!;c.beginSave(s.owner,s.epoch,s.review!!.id);assertNull(c.destination(false));assertNull(c.destination(true));assertFalse(c.state!!.confirmed);assertEquals(FormPhase.READY,c.state!!.phase)}
    @Test fun destinationCanWaitForReturnButCannotWriteWhileInactive(){val c=ready();val s=c.state!!;c.beginSave(s.owner,s.epoch,s.review!!.id);assertTrue(c.background(false));val t=c.destination(true)!!;assertFalse(c.startWrite(t));c.active(true);assertTrue(c.startWrite(t));c.saved(t,null);assertFalse(c.state!!.confirmed);assertFalse(c.startWrite(t))}
    @Test fun lockClearsContentAndReservesOldCallbacks(){val c=ready();val s=c.state!!;c.beginSave(s.owner,s.epoch,s.review!!.id);c.lock();assertNull(c.state);assertNull(c.destination(true));c.active(true);c.open();assertNull(c.state!!.model)}
    @Test fun pendingSourceMayReturnBehindLockButNeedsActivation(){val c=FormController();c.open();val s=c.state!!;c.beginInput(s.owner,s.epoch);c.lock();val t=c.picked(true)!!;assertFalse(c.matches(t));c.active(true);assertTrue(c.matches(t));c.loaded(t,model);assertEquals(FormPhase.EDIT,c.state!!.phase)}
    @Test fun backgroundClearsDraftAndRecreationOnlyRetainsLiveValues(){val c=loaded();val old=c.state!!;assertTrue(c.background(true));assertEquals(old,c.state);c.active(true);assertFalse(c.background(false));assertNull(c.state!!.model);assertTrue(c.state!!.values.isEmpty());c.active(true);c.loaded(FormToken(old.owner,old.epoch),model);assertNull(c.state!!.model)}
    @Test fun recreationCancelsPhysicalPreparationAndIgnoresItsCompletion(){val c=loaded();val t=review(c);assertTrue(c.background(true));c.active(true);c.prepared(t,null);assertEquals(FormPhase.REVIEW,c.state!!.phase);assertFalse(c.state!!.confirmed)}
}
