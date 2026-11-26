package org.synapseworks.pageharbor.qr

import org.junit.Assert.*
import org.junit.Test

class QrControllerTest {
    private val result=QrDecode.Found(QrPayload.accept("https://example.invalid/")!!)
    @Test fun firstResultStopsRepeatedFramesAndRescanAllowsSamePayload(){
        val c=QrController();assertTrue(c.open());assertFalse(c.open());val t=c.frame()!!;assertNull(c.frame())
        c.completeFrame(t,result);val s=c.state!!;assertEquals(QrPhase.RESULT,s.phase);assertNull(c.frame())
        c.completeFrame(t,QrDecode.Found(QrPayload.accept("Other synthetic")!!));assertSame(s,c.state)
        c.reviewLink(s.owner,s.generation);assertNotNull(c.destination(s.owner,s.generation))
        c.rescan();assertNull(c.destination(s.owner,s.generation));c.completeFrame(t,result);assertNull(c.state!!.result)
        assertNull(c.beginPicker(s.owner,s.generation));assertFalse(c.cameraAction(s.owner,s.generation))
        c.completeFrame(c.frame()!!,result);assertEquals(result.payload.text,c.state!!.result!!.text)
    }
    @Test fun closeBackgroundRecreationAndPermissionFailuresRejectOldFramesAndActions(){
        val c=QrController();c.open();val t=c.frame()!!;c.background(true);c.active(true);c.completeFrame(t,result);assertNull(c.state!!.result)
        c.completeFrame(c.frame()!!,result);val s=c.state!!;c.background(true);c.active(true);assertSame(s.result,c.state!!.result)
        c.background(false);assertNull(c.state!!.result);assertNull(c.result(s.owner,s.generation));c.active(true);c.rescan()
        val old=c.frame()!!;c.pause(QrProblem.PERMISSION);c.completeFrame(old,result);assertNull(c.state!!.result);assertNull(c.frame())
        c.close();c.open();c.completeFrame(old,result);assertNull(c.state!!.result)
    }
    @Test fun pickerReservationCannotBeMisattributedAfterExitOrRescanAndCanSurviveRecreation(){
        val c=QrController();c.open();val old=c.beginPicker()!!;c.close();c.open();assertNull(c.beginPicker())
        assertNull(c.receivePicker(true));assertNull(c.state!!.result)
        val t=c.beginPicker()!!;c.background(true);c.active(true);assertSame(t,c.receivePicker(true));assertTrue(c.currentImport(t))
        c.rescan();c.completeImport(t,result);assertNull(c.state!!.result)
        c.beginPicker();assertNull(c.receivePicker(false));assertEquals(QrPhase.PAUSED,c.state!!.phase)
        assertNotEquals(old.owner,c.state!!.owner)
    }
}
