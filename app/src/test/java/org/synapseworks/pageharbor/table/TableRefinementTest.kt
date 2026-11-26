package org.synapseworks.pageharbor.table

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

class TableRefinementTest {
    private fun raw(values:List<String> = listOf("BLUE B0OK")):TableExtraction {
        val lines=values.mapIndexed{i,v->OcrTextLine(v,OcrTextBounds(50f,50f+i*100,350f,90f+i*100))}
        return TableParser.parse(values.joinToString("\n"),OcrPageLayout(1000,2000,lines=lines))!!
    }
    @Test fun agreeingSourceReadingsProposeExactTextWithoutChangingRawEvidenceOrMarkingManualEdit(){
        val original=raw();val calls=mutableListOf<Int>()
        val result=TableRefinement.apply(original,1000,2000){_,_,h->calls+=h;"BLUE BOOK"}
        assertEquals(listOf(48,96),calls);assertEquals("BLUE BOOK",result.grid.values()[0][0]);assertEquals(original.source,result.source);assertEquals(original.units,result.units)
        val cell=result.grid.rows[0][0];assertFalse(cell.corrected);assertTrue(cell.uncertain);assertEquals(2,cell.readings.size);assertTrue(TableConcern.REREAD in result.concerns)
        assertEquals(original.grid.rows[0][0].id,cell.id);assertFalse(cell.toString().contains("BLUE"));assertFalse(cell.readings[0].toString().contains("BLUE"))
    }
    @Test fun conflictingAndMissingReadingsKeepRawAndExposeEachValidAlternative(){
        val original=raw();val result=TableRefinement.apply(original,1000,2000){_,_,h->if(h==48)"BLUE BOOK"else"BLUE B00K"}
        assertEquals(original.grid.values(),result.grid.values());assertEquals(listOf("BLUE BOOK","BLUE B00K"),result.grid.rows[0][0].readings.map{it.text})
        val partial=TableRefinement.apply(original,1000,2000){_,_,h->if(h==48)"BLUE BOOK"else null};assertEquals(original.grid.values(),partial.grid.values());assertEquals(1,partial.grid.rows[0][0].readings.size)
    }
    @Test fun literalZeroAndLetterOIdentifiersAndLeadingZeroNumbersNeverEnterRefinement(){
        val values=listOf("0000","0010","B00K","BOOK","O0O0","O0","0","O","-0.50")
        val original=raw(values);val result=TableRefinement.apply(original,1000,2000){_,_,_->fail("Identifier must not be reread");null};assertEquals(values,result.grid.values().map{it.single()})
    }
    @Test fun numericTokensAndLayoutWhitespaceArePreservedEvenWhenBothViewsSuggestDifferentText(){
        for((value,reading)in listOf("ROOM 0010" to "ROOM O010","COST -0.50" to "COST -O.50","MODEL 10" to "MODEL 100","BLUE BOOK" to "BLUEBOOK")){
            val original=raw(listOf(value));val result=TableRefinement.apply(original,1000,2000){_,_,_->reading};assertEquals(value,result.grid.values()[0][0])
        }
        for(value in listOf(" BLUE BOOK","BLUE  BOOK","BLUE\tBOOK","BLUE\nBOOK")){assertFalse(TableRefinement.eligible(TableCell(value,listOf(0))))}
    }
    @Test fun boundedReadingsRejectUnsafeHugeAndMultilineTextAndSkipUncertainGeometry(){
        for(text in listOf("x".repeat(1025),"BLUE\u202eBOOK","BLUE\nBOOK","")){
            val original=raw();val result=TableRefinement.apply(original,1000,2000){_,_,_->text};assertEquals(original.grid.values(),result.grid.values());assertTrue(result.grid.rows[0][0].readings.isEmpty())
        }
        val original=raw();val uncertain=original.copy(grid=TableGrid(listOf(listOf(original.grid.rows[0][0].copy(uncertain=true)))))
        assertEquals(uncertain,TableRefinement.apply(uncertain,1000,2000){_,_,_->fail();null})
        assertNull(TableRefinement.bounds(original.units.single(),100,2000))
    }
    @Test fun cropBudgetAndCancellationBoundWorkAndNeverPublishPartialResults(){
        val original=raw(List(12){"ROOM OPEN"});var calls=0
        val result=TableRefinement.apply(original,1000,2000){_,_,_->calls++;"ROOM OPEN"};assertEquals(16,calls);assertEquals(8,result.grid.rows.flatten().count{it.readings.isNotEmpty()})
        var checks=0;try{TableRefinement.apply(original,1000,2000,{if(++checks==4)throw kotlinx.coroutines.CancellationException()}){_,_,_->"ROOM CLOSED"};fail()}catch(_:kotlinx.coroutines.CancellationException){assertEquals(original.grid.values(),raw(List(12){"ROOM OPEN"}).grid.values())}
    }
    @Test fun reviewedCopyAndManualEditsRetainRawAndCropProvenanceWithFrozenBytes(){
        val result=TableRefinement.apply(raw(),1000,2000){_,_,_->"BLUE BOOK"};val review=TableReview.create(result.grid)!!
        val changed=result.grid.edit(result.grid.rows[0][0].id,"B00K O0 0010");assertEquals(2,changed.rows[0][0].readings.size)
        assertEquals("BLUE BOOK",review.grid.values()[0][0]);assertEquals("BLUE B0OK",result.units.single().text)
    }
}
