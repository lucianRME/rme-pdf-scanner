package org.synapseworks.pageharbor.table

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class TableCsvTest {
    @Test fun explicitUtf8QuoteCommaCrLfContractPreservesBlanksQuotesNewlinesAndUnicode() {
        val grid=TableGrid.from(listOf(listOf("0012","Café, \"large\"","a\r\nb\nc\rd",""),listOf("","","","")))
        val expected="\"0012\",\"Café, \"\"large\"\"\",\"a\r\nb\nc\rd\",\"\"\r\n\"\",\"\",\"\",\"\"\r\n"
        val bytes=TableCsv.encode(grid)!!;assertArrayEquals(expected.toByteArray(Charsets.UTF_8),bytes);assertFalse(bytes.take(3)==listOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte()))
        assertEquals("0012",grid.values()[0][0])
    }
    @Test fun frozenFormulaEscapingAndNumericExceptionsMatchIndependentLiteralValues() {
        assertEquals(FrozenTables.csvExpected,FrozenTables.csvInput.map{r->r.map(TableCsv::outgoing)})
        for(value in listOf("=1+2"," +SUM(1)","-1+2","@SUM(1)","\r1","\n2","\t3","\u00a0＝1","＋SUM(1)","－1+2","＠SUM(1)","'=1+2","\"=1+2","\u034F=1+2","\u20031\n")) {
            if(value=="\u20031\n")assertEquals(value,TableCsv.outgoing(value))else assertEquals("text: "+value,TableCsv.outgoing(value))
        }
        for(value in listOf("0012","-12.50","+12,50","-.5","+.5","-1e-3","+12E3","a@b","text: =1","(12.50)","Café","東京","📄"))assertEquals(value,TableCsv.outgoing(value))
    }
    @Test fun malformedUnicodeControlsAndExcessiveGridsCannotExport() {
        for(value in listOf("bad\u0000","bad\u202E","bad\uD800","x".repeat(1025)))assertNull(TableCsv.encode(TableGrid.from(listOf(listOf(value)))))
        assertNull(TableCsv.encode(TableGrid(emptyList())));assertNull(TableCsv.encode(TableGrid.from(listOf(listOf("a","b"),listOf("a")))))
        assertNull(TableCsv.encode(TableGrid.blank(41,1)));assertNull(TableCsv.encode(TableGrid.blank(1,13)))
        assertFalse(TableGrid.from(List(40){List(12){"x".repeat(1024)}}).valid())
    }
    @Test fun reviewedExportIsFrozenAcrossSubsequentEditsAndLayoutChanges() {
        val input=TableGrid.from(listOf(listOf("0012","=1+2")));val frozen=TableReview.create(input)!!
        val t=TableSaveToken("owner",1,frozen.id);val export=frozen.export(t);val edited=input.edit(input.rows[0][0].id,"0099").insertColumn(1)
        val out=ByteArrayOutputStream();assertTrue(export.write({out}){});assertEquals("\"0012\",\"text: =1+2\"\r\n",out.toString("UTF-8"));assertEquals("0099",edited.values()[0][0]);assertEquals(listOf(listOf("0012","=1+2")),frozen.grid.values())
    }
    @Test fun streamsCloseOnWriteFlushCloseFailureCancellationAndNullDestination() {
        val bytes=ByteArray(9000){65};var closed=0
        val bad=object:OutputStream(){override fun write(v:Int){throw IOException()};override fun write(b:ByteArray,o:Int,n:Int){throw IOException()};override fun close(){closed++}}
        assertFalse(TableCsvWriter.write(bytes,{bad}));assertEquals(1,closed)
        val flush=object:ByteArrayOutputStream(){override fun flush(){throw IOException()};override fun close(){closed++}}
        assertFalse(TableCsvWriter.write(bytes,{flush}));assertEquals(2,closed)
        val close=object:ByteArrayOutputStream(){override fun close(){closed++;throw IOException()}}
        assertFalse(TableCsvWriter.write(bytes,{close}));assertEquals(3,closed)
        var checks=0;val cancel=object:ByteArrayOutputStream(){override fun close(){closed++}}
        try{TableCsvWriter.write(bytes,{cancel}){if(++checks==3)throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){assertEquals(4096,cancel.size());assertEquals(4,closed)}
        var opened=false;try{TableCsvWriter.write(bytes,{opened=true;cancel}){throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){assertFalse(opened)}
        assertFalse(TableCsvWriter.write(bytes,{null}));assertFalse(TableCsvWriter.write(ByteArray(TableLimits.CSV+1),{fail();null}))
    }
}
