package org.synapseworks.pageharbor.docx

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Fixed write-only OOXML subset. No template/parser, external relationship, field or active content. */
internal object DocxWriter {
    private const val W="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAX=32L*1024*1024
    private fun twip(v:Float)=(v*20).roundToInt()
    private fun emu(v:Float)=(v*12700).roundToInt()
    fun write(document:DocxDocument,output:File,check:()->Unit={},progress:(Int)->Unit={}) {
        require(document.problem()==null);require(!output.exists()&&output.parentFile?.isDirectory==true)
        var success=false
        try {
            val pictures=document.pages.flatMap{it.blocks}.filter{it.kind==DocxKind.IMAGE}
            require(pictures.sumOf{it.picture!!.file.length()}<=20L*1024*1024)
            val pictureIds=pictures.mapIndexed{i,b->b.picture!!.file.canonicalPath to i+1}.toMap()
            require(pictureIds.size==pictures.size)
            var xmlBytes=0L
            FileOutputStream(output).use { raw ->
                val bounded=object:FilterOutputStream(raw) {
                    var count=0L
                    override fun write(b:Int){check();require(++count<=MAX);out.write(b)}
                    override fun write(b:ByteArray,off:Int,len:Int){check();count+=len;require(count<=MAX);out.write(b,off,len)}
                }
                ZipOutputStream(bounded).use { zip ->
                    fun part(name:String,text:String) { check();val b=text.toByteArray(Charsets.UTF_8);xmlBytes+=b.size;require(xmlBytes<=6L*1024*1024);zip.putNextEntry(ZipEntry(name).apply{time=315532800000L});zip.write(b);zip.closeEntry() }
                    part("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/></Types>""")
                    part("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="doc" Type="$R/officeDocument" Target="word/document.xml"/></Relationships>""")
                    part("word/styles.xml",styles())
                    part("word/numbering.xml",numbering(document))
                    part("word/_rels/document.xml.rels",buildString {
                        append("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="$R/styles" Target="styles.xml"/><Relationship Id="numbering" Type="$R/numbering" Target="numbering.xml"/>""")
                        pictureIds.values.forEach{append("""<Relationship Id="image$it" Type="$R/image" Target="media/image$it.jpg"/>""")};append("</Relationships>")
                    })
                    part("word/document.xml",body(document,pictureIds,check,progress))
                    pictures.forEach { b ->
                        check();val p=b.picture!!;require(p.width in 1..2048&&p.height in 1..2048&&p.width.toLong()*p.height<=4_194_304&&p.file.length() in 4..2L*1024*1024)
                        val bytes=p.file.readBytes();require(bytes[0]==0xff.toByte()&&bytes[1]==0xd8.toByte()&&bytes[bytes.size-2]==0xff.toByte()&&bytes.last()==0xd9.toByte())
                        zip.putNextEntry(ZipEntry("word/media/image${pictureIds[p.file.canonicalPath]}.jpg").apply{time=315532800000L});zip.write(bytes);zip.closeEntry()
                    }
                }
            }
            check();FileOutputStream(output,true).use{it.fd.sync()};require(output.length() in 1..MAX);success=true
        } finally { if(!success&&output.isFile)output.delete() }
    }
    private fun styles()="""<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="Arial"/><w:sz w:val="24"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="atLeast"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style></w:styles>"""
    private fun numberId(page:Int,b:DocxBlock)=page*1_000_001+b.id+10
    private fun numbering(doc:DocxDocument)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><w:numbering xmlns:w="$W"><w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="360"/></w:tabs><w:ind w:left="360" w:hanging="360"/></w:pPr><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/></w:rPr></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId="2"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:tabs><w:tab w:val="num" w:pos="360"/></w:tabs><w:ind w:left="360" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num>""")
        doc.pages.forEachIndexed { p,page -> page.blocks.filter{it.kind==DocxKind.NUMBER}.forEach { b ->
            val start=Regex("^\\s*(\\d{1,3})[.)]").find(b.text)?.groupValues?.get(1)?.toInt()?:1
            append("""<w:num w:numId="${numberId(p,b)}"><w:abstractNumId w:val="2"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="$start"/></w:lvlOverride></w:num>""")
        } };append("</w:numbering>")
    }
    private fun text(value:String)=buildString {
        val lines=value.replace("\r\n","\n").replace('\r','\n').split('\n')
        lines.forEachIndexed{i,line->if(i>0)append("<w:br/>");line.split('\t').forEachIndexed{j,s->if(j>0)append("<w:tab/>");append("<w:t xml:space=\"preserve\">${DocxText.xml(s)}</w:t>")}}
    }
    private fun p(b:DocxBlock,page:Int,before:Float,layout:Boolean):String=buildString {
        val heading=b.kind==DocxKind.HEADING;val line=if(heading)22 else 14
        append("<w:p><w:pPr>");if(heading)append("<w:pStyle w:val=\"Heading1\"/>")
        if(b.kind in setOf(DocxKind.BULLET,DocxKind.NUMBER))append("""<w:numPr><w:ilvl w:val="0"/><w:numId w:val="${if(b.kind==DocxKind.BULLET)1 else numberId(page,b)}"/></w:numPr>""")
        append("""<w:spacing w:before="${twip(before)}" w:after="${if(layout)0 else if(heading)160 else 120}" w:line="${line*20}" w:lineRule="atLeast"/></w:pPr><w:r>""")
        append(font(b.text));append(text(DocxText.content(b).let{if(layout)it else it.replace('\n',' ')}));append("</w:r></w:p>")
    }
    // Spacing paragraphs remain ordinary editable paragraphs. Fixed one-twip lines clipped
    // text entered by office applications and propagated a one-point paragraph-mark font.
    private fun empty(height:Float=14f)="""<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="${twip(height.coerceAtLeast(14f))}" w:lineRule="atLeast"/></w:pPr></w:p>"""
    private fun font(value:String)=if(value.any{it in '\u0370'..'\u03ff'||it=='\u00b5'})"""<w:rPr><w:rFonts w:ascii="Helvetica" w:hAnsi="Helvetica" w:eastAsia="Helvetica"/></w:rPr>"""else ""
    private fun picture(b:DocxBlock,id:Int,width:Float,height:Float,before:Float)="""<w:p><w:pPr><w:spacing w:before="${twip(before)}" w:after="0" w:line="${twip(height)}" w:lineRule="atLeast"/></w:pPr><w:r><w:rPr><w:sz w:val="2"/></w:rPr><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${emu(width)}" cy="${emu(height)}"/><wp:docPr id="$id" name="Selected figure $id" descr="Reviewed source image region"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="image$id.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="image$id"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${emu(width)}" cy="${emu(height)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    private fun table(b:DocxBlock,width:Float,height:Float):String=buildString {
        val grid=b.table!!;val cell=twip(width/grid.columns);val rowHeight=max(14f,height/grid.rows.size)
        append("""<w:tbl><w:tblPr><w:tblW w:w="${twip(width)}" w:type="dxa"/><w:tblBorders>""")
        for(edge in listOf("top","left","bottom","right","insideH","insideV"))append("<w:$edge w:val=\"single\" w:sz=\"4\" w:color=\"666666\"/>")
        append("""</w:tblBorders><w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar><w:tblCaption w:val="Reviewed source table"/></w:tblPr><w:tblGrid>""");repeat(grid.columns){append("<w:gridCol w:w=\"$cell\"/>")};append("</w:tblGrid>")
        grid.rows.forEachIndexed { i,row -> append("<w:tr><w:trPr><w:trHeight w:val=\"${twip(rowHeight)}\" w:hRule=\"atLeast\"/>");if(i==0)append("<w:tblHeader/>");append("</w:trPr>")
            row.forEach{c->append("<w:tc><w:tcPr><w:tcW w:w=\"$cell\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"280\" w:lineRule=\"atLeast\"/></w:pPr><w:r>${font(c.value)}${text(c.value)}</w:r></w:p></w:tc>")};append("</w:tr>")
        };append("</w:tbl>")
    }
    private fun section(doc:DocxDocument,columns:Int)="""<w:sectPr><w:type w:val="continuous"/><w:pgSz w:w="${twip(doc.paper.width)}" w:h="${twip(doc.paper.height)}"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="0" w:footer="0" w:gutter="0"/><w:cols w:num="$columns" w:space="480"/></w:sectPr>"""
    private fun body(doc:DocxDocument,ids:Map<String,Int>,check:()->Unit,progress:(Int)->Unit)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$W" xmlns:r="$R" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
        val width=doc.paper.width-72
        // A section boundary belongs to a body paragraph, never a table cell.
        fun endSection(columns:Int) {
            val end=lastIndexOf("</w:pPr>")
            require(end>=0);insert(end,section(doc,columns))
        }
        doc.pages.forEachIndexed { pageIndex,page ->
            check();val pageStart=length
            fun render(blocks:List<DocxBlock>,start:Float,available:Float,columnStart:Boolean=false,columnBreak:Boolean=false):Pair<String,Float> {
                var y=start;val out=StringBuilder()
                if(columnStart&&blocks.isNotEmpty()) {
                    val gap=max(0f,blocks.first().box!!.top*doc.paper.height-start)
                    if(gap>0) {
                        var spacer=empty(gap)
                        if(columnBreak)spacer=spacer.replace("</w:p>","<w:r><w:br w:type=\"column\"/></w:r></w:p>")
                        out.append(spacer);y+=max(14f,gap)
                    }
                }
                for(b in blocks) {
                    check();val layout=doc.mode==DocxMode.LAYOUT;val target=if(layout)b.box!!.top*doc.paper.height else y
                    require(!layout||target>=y-2f||columnStart&&b==blocks.first()){ "Layout exceeds supported fit" };val gap=if(layout)max(0f,target-y)else 0f
                    val h=when(b.kind){DocxKind.HEADING->22f;DocxKind.TABLE->max(14f*b.table!!.rows.size,if(layout)b.box!!.height*doc.paper.height else 20f*b.table.rows.size);DocxKind.IMAGE->if(layout)b.box!!.height*doc.paper.height else min(available,b.box?.width?.times(doc.paper.width)?:available)*b.picture!!.height/b.picture.width;else->14f*if(layout)b.text.lines().size else 1}
                    when(b.kind) {
                        DocxKind.TABLE->{if(gap>0)out.append(empty(gap));out.append(table(b,min(available,b.box?.width?.times(doc.paper.width)?:available),h));}
                        DocxKind.IMAGE->out.append(picture(b,ids.getValue(b.picture!!.file.canonicalPath),min(available,if(layout)b.box!!.width*doc.paper.width else b.box?.width?.times(doc.paper.width)?:available),h,gap))
                        else->out.append(p(b,pageIndex,gap,layout))
                    }
                    y=max(y,target)+h+(if(layout)0 else if(b.kind==DocxKind.HEADING)8 else 6)
                    require(!layout||y<=doc.paper.height-36+2){"Layout exceeds supported fit"}
                }
                if(columnBreak&&blocks.firstOrNull()?.box?.top?.times(doc.paper.height)?.let{it<=start}==true) {
                    val first=out.indexOf("<w:r>");require(first>=0);out.insert(first+5,"<w:br w:type=\"column\"/>")
                }
                if(blocks.lastOrNull()?.kind in setOf(DocxKind.TABLE,DocxKind.IMAGE))out.append(empty())
                return out.toString() to y
            }
            if(doc.mode==DocxMode.LAYOUT&&page.columns==2) {
                val heads=page.blocks.filter{it.column==-1};val head=render(heads,36f,width);append(head.first)
                if(heads.isNotEmpty())endSection(1)
                val col=(width-24)/2
                append(render(page.blocks.filter{it.column==0},head.second,col,columnStart=true).first)
                append(render(page.blocks.filter{it.column==1},head.second,col,columnStart=true,columnBreak=true).first)
            } else append(render(page.blocks,36f,width).first)
            if(pageIndex>0) {
                val first=indexOf("<w:pPr>",pageStart)
                require(first>=pageStart){"Missing page boundary paragraph"}
                val propertyStart=first+"<w:pPr>".length
                val afterStyle=if(substring(propertyStart).startsWith("<w:pStyle "))indexOf("/>",propertyStart)+2 else propertyStart
                insert(afterStyle,"<w:pageBreakBefore/>")
            }
            if(pageIndex<doc.pages.lastIndex)endSection(if(doc.mode==DocxMode.LAYOUT)page.columns else 1)
            progress(pageIndex+1)
        }
        append(section(doc,if(doc.mode==DocxMode.LAYOUT)doc.pages.last().columns else 1));append("</w:body></w:document>")
    }
}
