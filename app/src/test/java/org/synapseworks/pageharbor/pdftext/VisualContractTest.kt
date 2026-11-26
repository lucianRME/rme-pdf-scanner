package org.synapseworks.pageharbor.pdftext

import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.forms.*

class VisualContractTest {
    @Test fun sourceDisplayGeometryRoundTripsEveryQuarterTurn(){for(rotation in listOf(0,90,180,270)){val p=VisualPage(20f,20f,380f,320f,rotation);val a=AnnotationPoint(.2f,.7f);val restored=p.source(a).rotated(rotation);assertEquals(a.x,restored.x,.0001f);assertEquals(a.y,restored.y,.0001f);assertEquals(if(rotation%180==0)380f else 320f,p.displayWidth)}}
    @Test fun textScopeIsLiteralBoundedAndExcludesUnsafeGlyphs(){assertTrue(visualTextAllowed("Zoë €0020"));for(s in listOf(""," ","a\nb","漢字","😀","a\u00adb","W".repeat(257)))assertFalse(visualTextAllowed(s))}
    @Test fun frozenEditAndDiagnosticStringsDoNotExposeDraft(){val edit=VisualEdit(0,DEFAULT_VISUAL_REGION,"PRIVATE SYNTHETIC");assertFalse(edit.toString().contains(edit.text));assertEquals(DEFAULT_VISUAL_REGION,edit.region)}
    @Test fun changingModeCannotRebindReservedDestinationToAnotherOwner(){val c=FormController();c.open();var s=c.state!!;c.beginInput(s.owner,s.epoch);c.loaded(c.picked(true)!!,VisualDocument(listOf(VisualPage(0f,0f,420f,360f,0))).form());s=c.state!!;c.edit(s.owner,s.epoch,"visual",listOf("Zoë 0020"));c.review(s.owner,s.epoch);val r=c.state!!.review!!;c.confirm(s.owner,s.epoch,r.id,true);val t=c.prepare(s.owner,s.epoch,r.id)!!;c.prepared(t,null);assertTrue(c.beginSave(s.owner,s.epoch,r.id));c.close();c.open();assertNull(c.destination(true));assertNull(c.state!!.model)}
}
