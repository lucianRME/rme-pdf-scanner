package org.synapseworks.pageharbor.docx

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.Assert.*

class DocxCoreTest {
    private fun bytes(name:String)=javaClass.getResourceAsStream("/m32/$name")!!.use{it.readBytes()}
    private fun source(name:String,flat:Boolean=false)=FrozenDocxCorpus.ocr(String(bytes("$name.tsv")),!flat)
    @Test fun automaticSourceCoverageReadingOrderAndKindsRemainIndependentOfManualCorrection() {
        for(name in FrozenDocxCorpus.names.filter{it !in setOf("ambiguous","rtl-indic","overflow")}) {
            val rows=FrozenDocxCorpus.rows(String(bytes("$name.tsv")))
            for(ocr in source(name)) {
                val p=DocxReconstructor.page(ocr);val expected=rows.filter{it.page==ocr.pageIndex}.groupBy{it.block}.values.sortedWith(compareBy<List<FrozenDocxCorpus.Row>>{it.first().column}.thenBy{it.first().bounds.top}.thenBy{it.first().bounds.left})
                assertEquals(name,expected.flatMap{it.map{r->r.text}},p.blocks.flatMap{it.text.lines()})
                assertEquals(name,expected.map{it.first().kind},p.blocks.map{it.kind});assertTrue(p.blocks.none{it.corrected});assertEquals(ocr.layout!!.lines.indices.toSet(),p.blocks.flatMap{it.sourceIds}.toSet())
            }
        }
    }
    @Test fun independentSizeAndWhitespaceControlsRejectBodyLookalikesWithoutTextCorrection() {
        val frozen=javaClass.getResourceAsStream("/m32-followup/geometry-controls.json")!!.reader().use{it.readText()}
        for(record in Regex("\\{[^{}]+}").findAll(frozen)) {
            fun string(key:String)=Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(record.value)!!.groupValues[1]
            fun number(key:String)=Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(record.value)!!.groupValues[1].toFloat()
            val title=string("title");val bottom=50f+number("height");val next=bottom+number("gap")
            val lines=listOf(org.synapseworks.pageharbor.ocr.OcrTextLine(title,org.synapseworks.pageharbor.ocr.OcrTextBounds(40f,50f,40f+number("width"),bottom)))+(0..3).map{n->org.synapseworks.pageharbor.ocr.OcrTextLine("Ordinary text $n.",org.synapseworks.pageharbor.ocr.OcrTextBounds(40f,next+n*40,300f,next+n*40+20))}
            val ocr=org.synapseworks.pageharbor.ocr.OcrPageResult(0,lines.joinToString("\n"){it.text},layout=org.synapseworks.pageharbor.ocr.OcrPageLayout(612,792,lines=lines,blocks=lines.map{org.synapseworks.pageharbor.ocr.OcrTextBlock(listOf(it))}))
            val page=DocxReconstructor.page(ocr)
            assertEquals(string("name"),DocxKind.valueOf(string("kind")),page.blocks.first().kind)
            assertEquals(lines.map{it.text},page.blocks.map{it.text});assertEquals(lines.indices.toList(),page.blocks.flatMap{it.sourceIds});assertTrue(page.blocks.none{it.corrected})
        }
    }
    @Test fun editableSpacingAndUnicodeRemainExplicitWithoutTinyOrFixedTextParagraphs() {
        val root=Files.createTempDirectory("m32-growable-").toFile()
        try {
            val page=DocxReconstructor.page(source("mixed").single());val f=File(root,"unicode.docx")
            DocxWriter.write(DocxDocument(listOf(page)),f)
            ZipFile(f).use{z->val xml=z.getInputStream(z.getEntry("word/document.xml")).reader().readText();assertTrue(xml.contains("κείμενο"));assertFalse(xml.contains("κείµενο"));assertTrue(xml.contains("w:hAnsi=\"Helvetica\""));assertFalse(xml.contains("lineRule=\"exact\""))}
            val pages=source("pages").map{DocxReconstructor.page(it)};val out=File(root,"pages.docx");DocxWriter.write(DocxDocument(pages,DocxMode.LAYOUT),out)
            ZipFile(out).use{z->val xml=z.getInputStream(z.getEntry("word/document.xml")).reader().readText();assertEquals(1,Regex("<w:pageBreakBefore/>").findAll(xml).count());assertFalse(xml.contains("w:line=\"1\""));assertFalse(xml.contains("lineRule=\"exact\""))}
        } finally{root.deleteRecursively()}
    }
    @Test fun regionAssistedTableUsesM22BeforeCorrectionsAndRetainsEverySourceLineOnce() {
        val page=DocxReconstructor.page(source("table").single());val result=DocxReconstructor.table(page,FrozenDocxCorpus.tableRegion("table")!!)
        val table=result.blocks.single{it.kind==DocxKind.TABLE};assertFalse(table.corrected)
        assertEquals(listOf(listOf("Code","Qty","Value"),listOf("A-10","2","12.50"),listOf("B-20","3","18.75"),listOf("Total","5","31.25")),table.table!!.values())
        assertEquals(page.lines.size,result.blocks.sumOf{it.sourceIds.size});assertNull(DocxDocument(listOf(result)).problem())
    }
    @Test fun ambiguityUnsupportedScriptsAndIncompleteEvidenceStayVisible() {
        val ambiguous=DocxReconstructor.page(source("ambiguous").single());assertTrue(ambiguous.concerns.any{it.startsWith("LAYOUT:")});assertNotNull(DocxDocument(listOf(ambiguous),DocxMode.LAYOUT).problem())
        assertNotNull(DocxDocument(listOf(DocxReconstructor.page(source("rtl-indic").single()))).problem())
        val page=DocxReconstructor.page(source("letter").single());assertNotNull(DocxDocument(listOf(page.copy(blocks=page.blocks.drop(1)))).problem())
        assertTrue(DocxText.safe("café Ελληνικά Пример 中文 日本語 한국어"));for(s in listOf("\u0000","\uD800","\uDC00","\u202E","مرحبا","नमस्ते","\uD83D\uDE00"))assertFalse(DocxText.safe(s))
    }
    @Test fun unambiguousUnicodeBulletWithoutOcrSpaceIsAListProposalWithoutDuplicatingMarker() {
        val p=DocxReconstructor.page(org.synapseworks.pageharbor.ocr.OcrPageResult(0,"•Second item"));val b=p.blocks.single()
        assertEquals("•Second item",b.text);assertEquals(DocxKind.BULLET,b.kind);assertFalse(b.corrected);assertEquals("Second item",DocxText.content(b))
        assertFalse(DocxText.isBullet("-20 degrees"));assertFalse(DocxText.isBullet("*emphasis*"))
    }
    @Test fun authoritativeFreeformTextDoesNotClaimPositionedLayoutAndMismatchDeclines() {
        val original=source("letter").single()
        val corrected=original.copy(text="Reviewed freeform text",layout=original.layout!!.copy(lines=listOf(org.synapseworks.pageharbor.ocr.OcrTextLine("Reviewed freeform text",org.synapseworks.pageharbor.ocr.OcrTextBounds(36f,36f,540f,700f))),blocks=emptyList(),placementMode=org.synapseworks.pageharbor.ocr.OcrTextPlacementMode.FREEFORM_PAGE_REGION))
        val p=DocxReconstructor.page(corrected);assertEquals(DocxOrigin.FREEFORM_OCR,p.origin);assertEquals(DocxKind.BODY,p.blocks.single().kind);assertEquals(corrected.text,p.sourceText);assertNotNull(DocxDocument(listOf(p),DocxMode.LAYOUT).problem())
        assertThrows(IllegalArgumentException::class.java){DocxReconstructor.page(original.copy(text="Different authoritative text"))}
    }
    @Test fun packagesContainEditableStructuresAndNoPrivateOrActivePartsAndCleanFailures() {
        val root=Files.createTempDirectory("m32-jvm-").toFile()
        try {
            val reports=File("build/reports/m32-docx").apply{mkdirs()}
            for(name in listOf("letter","columns","table","mixed","pages","native")) {
                var pages=source(name).map{DocxReconstructor.page(it)}
                FrozenDocxCorpus.tableRegion(name)?.let{pages=listOf(DocxReconstructor.table(pages.single(),it))}
                FrozenDocxCorpus.imageRegion(name)?.let { region ->
                    val source=ImageIO.read(bytes("$name-0.png").inputStream());val image=source.getSubimage((region.left*source.width).toInt(),(region.top*source.height).toInt(),(region.width*source.width).toInt(),(region.height*source.height).toInt());val file=File(root,"$name.jpg");val writer=ImageIO.getImageWritersByFormatName("jpeg").next();ImageIO.createImageOutputStream(file).use{stream->writer.output=stream;val options=writer.defaultWriteParam.apply{compressionMode=javax.imageio.ImageWriteParam.MODE_EXPLICIT;compressionQuality=.9f};writer.write(null,javax.imageio.IIOImage(image,null,null),options)};writer.dispose()
                    val page=pages.single();val block=DocxBlock(900,DocxKind.IMAGE,"",emptyList(),region,if(name=="columns")1 else 0,picture=DocxPicture(file,image.width,image.height),corrected=true,note="Manual figure selection")
                    pages=listOf(page.copy(blocks=(page.blocks+block).sortedWith(compareBy<DocxBlock>{it.column}.thenBy{it.box?.top?:0f})))
                }
                for(mode in DocxMode.entries) {
                    val file=File(root,"$name-$mode.docx");DocxWriter.write(DocxDocument(pages,mode),file);file.copyTo(File(reports,file.name),overwrite=true)
                    ZipFile(file).use { zip -> val names=zip.entries().asSequence().map{it.name}.toSet();assertTrue(names.containsAll(listOf("[Content_Types].xml","word/document.xml","word/styles.xml","word/numbering.xml")));assertTrue(names.none{it.contains("docProps")||it.contains("vba",true)||it.contains("template",true)})
                        val xml=zip.getInputStream(zip.getEntry("word/document.xml")).reader().readText();assertTrue(xml.contains("<w:p>"));assertFalse(xml.contains("fldChar"));assertFalse(xml.contains("hyperlink"));if(name=="table")assertTrue(xml.contains("Reviewed source table"))
                        if(name=="columns"&&mode==DocxMode.LAYOUT){assertTrue(xml.contains("<w:cols w:num=\"2\""));assertTrue(xml.contains("<w:br w:type=\"column\"/>"));assertFalse(xml.contains("Layout columns, not source table"))}
                        val rel=zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).reader().readText();assertFalse(rel.contains("External"))
                    }
                }
            }
            val invalid=File(root,"cancelled.docx");assertThrows(IllegalStateException::class.java){DocxWriter.write(DocxDocument(listOf(DocxReconstructor.page(source("letter").single()))),invalid,{error("Synthetic cancellation")})};assertFalse(invalid.exists())
            val partial=File(root,"partial.docx");assertThrows(IllegalStateException::class.java){DocxWriter.write(DocxDocument(listOf(DocxReconstructor.page(source("letter").single()))),partial,progress={error("Synthetic cancellation after parts were written")})};assertFalse(partial.exists())
        } finally{root.deleteRecursively()}
    }
}
