package org.synapseworks.pageharbor.table

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

class TableParserTest {
    @Test fun independentFrozenGridsPreserveEveryValueBlankAndUncertainty() {
        assertEquals(10,FrozenTables.cases.size)
        for(f in FrozenTables.cases){val r=TableParser.parse(f.text,f.layout())!!
            assertEquals(f.name,f.expected,r.grid.values());assertEquals(f.name,f.uncertain,r.concerns.isNotEmpty());assertEquals(f.text,r.source)
            val ids=r.grid.rows.flatten().flatMap{it.evidence};assertEquals(r.units.size,ids.size);assertEquals(r.units.map{it.id}.toSet(),ids.toSet())
            assertTrue(r.units.all{it.text==f.lines[it.line].text.substring(it.start,it.end)})
        }
    }
    @Test fun spatialRunsRetainInternalSpacingWithoutTreatingWordsAsColumns() {
        val line=OcrTextLine("0012    BLUE  BOOK    2",OcrTextBounds(80f,300f,1240f,350f),elements=listOf(
            OcrTextElement("0012",OcrTextBounds(80f,300f,200f,350f)),OcrTextElement("BLUE",OcrTextBounds(650f,300f,780f,350f)),
            OcrTextElement("BOOK",OcrTextBounds(800f,300f,930f,350f)),OcrTextElement("2",OcrTextBounds(1180f,300f,1240f,350f))))
        val header=FrozenTables.cases.first().lines.take(3);val r=TableParser.parse("original source",OcrPageLayout(1600,1600,lines=header+line))!!
        assertEquals(listOf("0012","BLUE  BOOK","2"),r.grid.values()[1]);assertEquals("original source",r.source)
    }
    @Test fun inconsistentElementMappingAndAbsentGeometryRemainExplicitReviewDrafts() {
        val line=OcrTextLine("A | B",OcrTextBounds(50f,100f,500f,150f),elements=listOf(OcrTextElement("A",OcrTextBounds(50f,100f,100f,150f)),OcrTextElement("B",OcrTextBounds(400f,100f,500f,150f))))
        val r=TableParser.parse(line.text,OcrPageLayout(1600,1600,lines=listOf(line)))!!;assertTrue(TableConcern.ELEMENTS in r.concerns);assertEquals(listOf(listOf("A | B")),r.grid.values())
        val raw=TableParser.parse("alpha\nbeta",null)!!;assertEquals(listOf(listOf("alpha"),listOf("beta")),raw.grid.values());assertTrue(TableConcern.NO_GEOMETRY in raw.concerns)
        val rotated=TableParser.parse("A | B",OcrPageLayout(1600,1600,90,listOf(line)))!!;assertTrue(TableConcern.NO_GEOMETRY in rotated.concerns)
    }
    @Test fun overlappingRunsPreserveBothTextsAndFlagCollision() {
        val lines=listOf(OcrTextLine("A",OcrTextBounds(50f,100f,160f,150f)),OcrTextLine("B",OcrTextBounds(100f,105f,200f,155f)),OcrTextLine("C",OcrTextBounds(700f,100f,800f,150f)),
            OcrTextLine("D",OcrTextBounds(50f,300f,100f,350f)),OcrTextLine("E",OcrTextBounds(400f,300f,500f,350f)),OcrTextLine("F",OcrTextBounds(700f,300f,800f,350f)))
        val r=TableParser.parse("A\nB\nC\nD\nE\nF",OcrPageLayout(1600,1600,lines=lines))!!
        assertTrue(TableConcern.ALIGNMENT in r.concerns||TableConcern.COLLISION in r.concerns)
        assertEquals(6,r.grid.rows.flatten().sumOf{it.evidence.size})
    }
    @Test fun capsAndCancellationNeverSilentlyTruncateTables() {
        assertNull(TableParser.parse("x".repeat(32769),null));assertNull(TableParser.parse((1..41).joinToString("\n"){"line"},null))
        val cells=List(13){OcrTextLine("X",OcrTextBounds(it*100f,10f,it*100f+50,60f))};assertNull(TableParser.parse("X",OcrPageLayout(1600,1600,lines=cells)))
        var checks=0;try{TableParser.parse("A",null){checks++;throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){assertTrue(checks>0)}
    }
    @Test fun cellAndLayoutCorrectionsKeepStableEvidenceAndPositionedBlanks() {
        val r=TableParser.parse(FrozenTables.cases[0].text,FrozenTables.cases[0].layout())!!;val original=r.grid.rows[1][0]
        val edited=r.grid.edit(original.id,"0099");assertEquals(listOf("0099","BLUE BOOK","2"),edited.values()[1]);assertEquals(original.evidence,edited.rows[1][0].evidence)
        val moved=edited.moveColumn(0,2).moveRow(1,2);assertEquals("0099",moved.values()[2][2]);assertEquals(original.id,moved.rows[2][2].id)
        val expanded=moved.insertColumn(1).insertRow(1);assertEquals(List(4){""},expanded.values()[1]);assertTrue(expanded.rows.all{it[1].value.isEmpty()})
        assertEquals(moved.values(),expanded.deleteRow(1).deleteColumn(1).values());assertTrue(TableGrid.blank(40,12).insertRow(0).insertColumn(0).valid())
        assertEquals(listOf(listOf("")),TableGrid.blank(1,1).deleteRow(0).deleteColumn(0).values())
        assertEquals("0012",r.grid.values()[1][0]);assertFalse(r.toString().contains("0012"))
    }
}
