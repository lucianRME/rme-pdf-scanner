package org.synapseworks.pageharbor.pdftext

import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.forms.*

class TextContractTest {
    private val model=TextDocument(listOf(TextRun(1,10,"SAME",20f,50f,12f,40f),TextRun(1,20,"SAME",20f,80f,12f,40f)),1)
    @Test fun repeatedTextNeverCollapsesOccurrenceIdentity(){val f=model.form();assertEquals(setOf("1:10","1:20"),f.initial().keys);assertEquals(2,f.fields.size)}
    @Test fun frozenReviewOwnsItsValuesAndIgnoresEditsAfterPreparation(){val c=FormController();c.open();var s=c.state!!;c.beginInput(s.owner,s.epoch);c.loaded(c.picked(true)!!,model.form());s=c.state!!;c.edit(s.owner,s.epoch,"1:20",listOf("EDIT"));c.review(s.owner,s.epoch);val r=c.state!!.review!!;c.edit(s.owner,s.epoch,"1:10",listOf("BAD"));assertEquals(listOf("SAME"),r.values["1:10"]);assertEquals(listOf("EDIT"),r.values["1:20"]);assertNull(c.prepare(s.owner,s.epoch,r.id))}
    @Test fun textModelsAndSharedStateDoNotExposeContentInDiagnostics(){assertFalse(model.toString().contains("SAME"));assertFalse(model.runs[0].toString().contains("SAME"));assertFalse(model.form().toString().contains("SAME"))}
}
