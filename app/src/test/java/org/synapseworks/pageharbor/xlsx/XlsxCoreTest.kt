package org.synapseworks.pageharbor.xlsx

import org.junit.Test
import org.junit.Assert.*
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.table.*
import java.util.Base64
import java.nio.file.Files
import java.io.File
import java.util.zip.ZipFile
import java.math.BigDecimal

class XlsxCoreTest {
    @Test fun frozenTypesAreExactAndAmbiguityPrecisionAndLeadingZerosAreNeverCoerced() {
        val data=javaClass.getResourceAsStream("/m33/typed-cells.tsv")!!.reader().use{it.readText()}
        for(line in data.lineSequence().filter{it.isNotBlank()}) {
            val p=line.split('\t');fun decode(s:String)=String(Base64.getDecoder().decode(s),Charsets.UTF_8)
            val rule=runCatching{ReceiptDecimal.valueOf(p[3])}.getOrDefault(ReceiptDecimal.AUTO)
            val order=runCatching{ReceiptDateOrder.valueOf(p[3])}.getOrDefault(ReceiptDateOrder.AUTO)
            val value=XlsxValues.value(decode(p[2]),XlsxTyping(XlsxType.valueOf(p[1]),rule,order,p[4]))
            assertEquals(p[0],p[6].toBoolean(),value!=null);if(value!=null)assertEquals(p[0],decode(p[5]),value)
        }
        assertNull(XlsxValues.value("12.50",XlsxTyping(XlsxType.MONEY,ReceiptDecimal.DOT)))
        assertEquals(61L,XlsxValues.serial("1900-03-01"));assertEquals(46146L,XlsxValues.serial("2026-05-04"))
    }
    @Test fun xmlNoncharactersAndMalformedUtf16DeclineWithoutPublishing(){
        for(value in listOf("\uFFFE","\uFFFF","\uD800"))assertFalse(XlsxText.safe(value))
        assertTrue(XlsxText.safe("Café μ µ 中文\t\r\n"))
        val root=Files.createTempDirectory("m33-xml-").toFile()
        try{val table=FrozenXlsxCorpus.table();val output=File(root,"invalid.xlsx")
            assertThrows(IllegalArgumentException::class.java){XlsxWriter.write(XlsxBook(listOf(table.copy(source=table.source.copy(raw="\uFFFE")))),output)}
            assertFalse(output.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun reusedReceiptExtractionIsMeasuredBeforeCorrectionsAndMissingIsNotZero() {
        val e=FrozenXlsxCorpus.expenses()
        assertEquals(listOf("12.50","-2.50","5.25","",""),e.map{it.amount})
        assertEquals(listOf("EUR","EUR","USD","EUR",""),e.map{it.currency});assertTrue(e.all{it.corrected.isEmpty()})
        assertEquals(BigDecimal("10.00"),XlsxValues.totals(XlsxBook(expenses=e.take(3)))["EUR"])
        assertNull(XlsxValues.totals(XlsxBook(expenses=e.take(4)))["EUR"])
        assertNull(XlsxValues.totals(XlsxBook(expenses=e))[""])
        val changed=e.take(3).toMutableList().apply{set(0,get(0).copy(amount="22.50"))}
        assertEquals(BigDecimal("20.00"),XlsxValues.totals(XlsxBook(expenses=changed))["EUR"])
    }
    @Test fun mergesNeverDropCoveredTextAndSnapshotsDoNotRetarget() {
        val table=FrozenXlsxCorpus.table();assertNull(XlsxBook(listOf(table)).problem())
        assertNotNull(XlsxBook(listOf(table.copy(merges=listOf(XlsxMerge(1,0,1,1))))).problem())
        assertNotNull(XlsxBook(listOf(table.copy(merges=table.merges+table.merges))).problem())
        val source=mutableMapOf("key" to "Original");val snapshot=XlsxBook(listOf(table.copy(source=table.source.copy(original=source)))).owned();source["key"]="Changed"
        assertEquals("Original",snapshot.tables.single().source.original["key"])
        assertFalse(XlsxText.sheetName("[external]"));assertFalse(XlsxText.sheetName("Sources/unsafe"))
        assertNotNull(XlsxBook(listOf(table.copy(name="Sources"))).problem())
    }
    @Test fun existingM22GeometryAndSourceReferencesSurviveCorrections() {
        for(f in FrozenTables.cases) {
            val extraction=requireNotNull(TableParser.parse(f.text,f.layout()));assertEquals(f.expected,extraction.grid.values())
            val first=extraction.grid.rows.first().first();val edited=extraction.grid.edit(first.id,"Reviewed literal")
            val state=TableState("capture",7,TablePhase.REVIEW,extraction,edited,TableReview.create(edited),confirmed=true)
            val table=XlsxCapture.table(state,1,"Positioned test layout")
            assertEquals(first.value,table.source.original[first.id]);assertEquals("S1 P1 R1 C1",table.source.references[first.id]);assertEquals("Reviewed literal",table.grid.rows.first().first().value)
            assertTrue(table.source.evidence[first.id].orEmpty().isNotEmpty());assertEquals(extraction.source,table.source.raw)
        }
    }
    @Test fun generatedPackagesContainOnlyTypedDataAndTrustedTotalsAndCleanCancellation() {
        val root=Files.createTempDirectory("m33-").toFile()
        try {
            val report=File("build/reports/m33-xlsx").apply{mkdirs()}
            for((name,book)in FrozenXlsxCorpus.books()) {
                assertNull(name,book.problem());val output=File(root,"$name.xlsx");XlsxWriter.write(book,output);output.copyTo(File(report,output.name),true)
                ZipFile(output).use{z->
                    val paths=z.entries().asSequence().map{it.name}.toList();assertTrue(paths.none{it.contains("vba",true)||it.contains("external",true)||it.contains("docProps")})
                    val styles=z.getInputStream(z.getEntry("xl/styles.xml")).reader().readText()
                    assertTrue(styles.contains("<cellXfs count=\"20\"><xf numFmtId=\"49\""))
                    val sheets=paths.filter{it.startsWith("xl/worksheets/")}.map{z.getInputStream(z.getEntry(it)).reader().readText()}
                    if(name in setOf("types","literals")){assertTrue(sheets.all{!it.contains("<f>")})}
                    else if(name=="tables"){assertTrue(sheets.first().contains("t=\"inlineStr\"><is><t xml:space=\"preserve\">=1+1"));assertTrue(sheets.first().contains("mergeCell ref=\"A4:D4\""));assertTrue(sheets.first().contains("First line\nSecond line"));assertTrue(sheets.all{!it.contains("<f>")})}
                    else assertTrue(sheets.first().contains("ROUND(SUM("))
                    assertTrue(sheets.none{it.contains("hyperlink")||it.contains("connection")})
                }
            }
            val cancelled=File(root,"cancelled.xlsx");assertThrows(IllegalStateException::class.java){XlsxWriter.write(FrozenXlsxCorpus.books().getValue("tables"),cancelled,{error("Synthetic cancellation")})};assertFalse(cancelled.exists())
            val interrupted=File(root,"interrupted.xlsx");assertThrows(IllegalStateException::class.java){XlsxWriter.write(FrozenXlsxCorpus.books().getValue("tables"),interrupted,progress={error("Synthetic progress cancellation")})};assertFalse(interrupted.exists())
            val tall=FrozenXlsxCorpus.table().let{it.copy(grid=it.grid.edit(it.grid.rows[2][3].id,"line\n".repeat(50)))}
            val overflow=File(root,"too-tall.xlsx");assertThrows(IllegalArgumentException::class.java){XlsxWriter.write(XlsxBook(listOf(tall)),overflow)};assertFalse(overflow.exists())
        }finally{root.deleteRecursively()}
    }
}
