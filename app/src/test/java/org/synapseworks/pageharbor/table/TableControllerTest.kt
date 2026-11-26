package org.synapseworks.pageharbor.table

import org.junit.Assert.*
import org.junit.Test

class TableControllerTest {
    private fun ready():TableController=TableController().apply{open();manual();val s=state!!;edit(s.owner,s.epoch,s.grid.rows[0][0].id,"0012");review(s.owner,s.epoch);confirm(s.owner,s.epoch,state!!.review!!.id,true)}
    private fun pick(c:TableController):TableSaveToken{val s=c.state!!;assertTrue(c.beginSave(s.owner,s.epoch,s.review!!.id));return c.destination(true)!!}
    @Test fun explicitReviewAndConfirmationPrecedeEachSaveAndOnlyOneCallbackCanWrite(){
        val c=ready();val s=c.state!!;val review=s.review!!;c.confirm(s.owner,s.epoch,review.id,false);assertFalse(c.beginSave(s.owner,s.epoch,review.id));c.confirm(s.owner,s.epoch,review.id,true)
        assertTrue(c.beginSave(s.owner,s.epoch,review.id));assertFalse(c.beginSave(s.owner,s.epoch,review.id));val token=c.destination(true)!!;assertNull(c.destination(true));assertNotNull(c.startWrite(token));assertNull(c.startWrite(token));assertTrue(c.canWrite(token));c.saved(token,true)
        assertEquals(TablePhase.REVIEW,c.state!!.phase);assertFalse(c.state!!.confirmed);assertEquals("CSV saved.",c.state!!.problem)
    }
    @Test fun oldReviewCannotExportNewEditsAndPendingDestinationCannotBeReassigned(){
        val c=ready();val old=c.state!!;assertTrue(c.beginSave(old.owner,old.epoch,old.review!!.id));c.cancelExport();var s=c.state!!
        c.edit(s.owner,s.epoch,s.grid.rows[0][0].id,"0099");c.review(s.owner,s.epoch);s=c.state!!;c.confirm(s.owner,s.epoch,s.review!!.id,true)
        assertFalse(c.beginSave(s.owner,s.epoch,old.review.id));assertFalse(c.beginSave(s.owner,s.epoch,s.review.id));assertNull(c.destination(true));assertTrue(c.beginSave(s.owner,s.epoch,s.review.id))
        val token=c.destination(true)!!;val data=c.startWrite(token)!!;val out=java.io.ByteArrayOutputStream();assertTrue(data.write({out}){});assertTrue(out.toString("UTF-8").contains("0099"));assertFalse(out.toString("UTF-8").contains("0012"))
    }
    @Test fun sourceResetAndLockDropExportBeforeAnyDestinationIsOpened(){
        val c=ready();val old=c.state!!;assertTrue(c.beginSave(old.owner,old.epoch,old.review!!.id));c.lock();assertNull(c.state);c.setActive(true);c.open();assertNull(c.destination(true));assertNull(c.state!!.extraction)
        c.close();c.setActive(true);c.open();c.manual();val s=c.state!!;c.review(s.owner,s.epoch);c.confirm(s.owner,s.epoch,c.state!!.review!!.id,true);val token=pick(c);c.reset();assertNull(c.startWrite(token));assertFalse(c.canWrite(token))
    }
    @Test fun pickerResultCanWaitForForegroundButNeverForNewOwnerAfterLock(){
        val c=ready();val s=c.state!!;assertTrue(c.beginSave(s.owner,s.epoch,s.review!!.id));c.background(false);val token=c.destination(true)!!;assertNull(c.startWrite(token));assertEquals(TablePhase.WAITING,c.state!!.phase)
        c.background(true);c.setActive(true);assertNotNull(c.startWrite(token));c.lock();assertFalse(c.canWrite(token));assertNull(c.state)
    }
    @Test fun canceledPickerAndFailedWritesRequireAnotherExplicitConfirmation(){
        val c=ready();var s=c.state!!;assertTrue(c.beginSave(s.owner,s.epoch,s.review!!.id));assertNull(c.destination(false));assertEquals(TablePhase.REVIEW,c.state!!.phase);assertFalse(c.state!!.confirmed)
        s=c.state!!;c.confirm(s.owner,s.epoch,s.review!!.id,true);val token=pick(c);assertNotNull(c.startWrite(token));c.saved(token,false);assertTrue(c.state!!.problem!!.contains("partial CSV"));assertFalse(c.state!!.confirmed)
    }
    @Test fun recreationRetainsReviewButAbortsInFlightWriteWithoutDuplicatingIt(){
        val c=ready();val original=c.state!!.grid.values();c.background(true);c.setActive(true);assertEquals(original,c.state!!.grid.values());assertNotNull(c.state!!.review)
        val token=pick(c);c.startWrite(token);c.background(true);c.setActive(true);assertEquals(TablePhase.EDIT,c.state!!.phase);assertNull(c.state!!.review);assertEquals(original,c.state!!.grid.values());assertFalse(c.canWrite(token));c.saved(token,true);assertTrue(c.state!!.problem!!.contains("partial CSV"))
        c.background(false);c.setActive(true);assertNull(c.state!!.extraction);assertTrue(c.state!!.grid.rows.flatten().all{it.value.isEmpty()})
    }
    @Test fun staleImageCaptureAndCellIdsCannotReplaceCurrentSource(){
        val c=TableController();c.open();var s=c.state!!;assertTrue(c.beginPicker(s.owner,s.epoch));val token=c.picked(true)!!;c.reset();c.complete(token,TableParser.parse("stale",null));assertNull(c.state!!.extraction)
        s=c.state!!;val capture=c.capture(s.owner,s.epoch)!!;assertNull(c.capture(s.owner,s.epoch));c.reset();assertFalse(c.captured(capture))
        c.manual();s=c.state!!;val cell=s.grid.rows[0][0];c.layout(s.owner,s.epoch){it.deleteRow(0)};c.edit(s.owner,s.epoch,cell.id,"stale");assertTrue(c.state!!.grid.rows.flatten().none{it.value=="stale"})
        c.review(s.owner,s.epoch);c.correct();assertNull(c.state!!.review);assertFalse(c.state!!.confirmed)
    }
    @Test fun malformedManualValuesBlockReviewWithoutExposingContentInStateDiagnostics(){
        val c=TableController();c.open();c.manual();val s=c.state!!;c.edit(s.owner,s.epoch,s.grid.rows[0][0].id,"private\u202Edata");c.review(s.owner,s.epoch);assertEquals(TablePhase.EDIT,c.state!!.phase);assertNotNull(c.state!!.problem);assertFalse(c.state.toString().contains("private"))
    }
}
