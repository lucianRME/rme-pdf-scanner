package org.synapseworks.pageharbor.pptx

import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import kotlin.math.roundToLong
import org.synapseworks.pageharbor.docx.DocxText

/** Fixed write-only OPC/PresentationML subset. No source package/template parser or active parts. */
internal object PptxWriter {
    private const val P="http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A="http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL="http://schemas.openxmlformats.org/package/2006/relationships"
    private fun xml(s:String)=DocxText.xml(s).replace("\r","&#13;")
    private fun emu(v:Float)=(v*12700.0).roundToLong()
    private val group="<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
    private val colorMap="<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
    private fun rel(id:String,type:String,target:String)="<Relationship Id=\"$id\" Type=\"$R/$type\" Target=\"$target\"/>"
    private fun relationships(body:String)="<?xml version=\"1.0\"?><Relationships xmlns=\"$REL\">$body</Relationships>"
    private fun frame(b:PptxRect)="<a:xfrm><a:off x=\"${emu(b.x)}\" y=\"${emu(b.y)}\"/><a:ext cx=\"${emu(b.width)}\" cy=\"${emu(b.height)}\"/></a:xfrm>"
    fun digest(file:File):String=file.inputStream().use{input->val md=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)};md.digest().joinToString(""){"%02x".format(it)}}
    fun write(deck:PptxDeck,output:File,check:()->Unit={},progress:(Int)->Unit={}) {
        require(!output.exists()&&output.parentFile?.isDirectory==true&&deck.problem()==null)
        var accepted=false
        try {
            val media=mutableListOf<PptxMedia>();val slides=mutableListOf<Pair<String,String>>()
            for((i,page)in deck.pages.withIndex()) {
                check();val objects=if(deck.mode==PptxMode.IMAGE)listOf(PptxItem(0,PptxKind.IMAGE,null,media=page.visual))else page.items
                val links=StringBuilder(rel("layout","slideLayout","../slideLayouts/slideLayout1.xml"));val tree=StringBuilder(group)
                for((n,item)in objects.withIndex()) {
                    check();val box=if(deck.mode==PptxMode.IMAGE)PptxGeometry.page(page,deck.size)else PptxGeometry.item(page,deck.size,requireNotNull(item.box))
                    if(item.kind==PptxKind.IMAGE){val m=requireNotNull(item.media);require(m.file.length() in 1..(if(deck.mode==PptxMode.IMAGE)24L*1024*1024 else 2L*1024*1024));media+=m;val id="image${media.size}";links.append(rel(id,"image","../media/$id.jpg"));tree.append(picture(n+2,box,id))}
                    else tree.append(shape(n+2,item,box,PptxGeometry.leading(page,item,deck.size)))
                }
                slides+="<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:p=\"$P\" xmlns:a=\"$A\" xmlns:r=\"$R\"><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$tree</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>" to relationships(links.toString())
                progress(i+1)
            }
            require(media.sumOf{it.file.length()}<=24L*1024*1024)
            var xmlBytes=0L
            FileOutputStream(output).use{raw->
                val bounded=object:FilterOutputStream(raw){var bytes=0L;override fun write(b:Int){check();require(++bytes<=PPTX_MAX);out.write(b)};override fun write(b:ByteArray,off:Int,len:Int){check();bytes+=len;require(bytes<=PPTX_MAX);out.write(b,off,len)}}
                ZipOutputStream(bounded).use{zip->
                    fun part(name:String,value:String){check();val bytes=value.toByteArray();xmlBytes+=bytes.size;require(xmlBytes<=6L*1024*1024);zip.putNextEntry(ZipEntry(name).apply{time=315532800000L});zip.write(bytes);zip.closeEntry()}
                    val types=linkedMapOf("presentation.xml" to "presentation","slideMasters/slideMaster1.xml" to "slideMaster","slideLayouts/slideLayout1.xml" to "slideLayout")
                    slides.indices.forEach{types["slides/slide${it+1}.xml"]="slide"}
                    part("[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>${types.entries.joinToString(""){(n,t)->"<Override PartName=\"/ppt/$n\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.$t${if(t=="presentation")".main"else""}+xml\"/>"}}<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/></Types>")
                    part("_rels/.rels",relationships(rel("presentation","officeDocument","ppt/presentation.xml")))
                    part("ppt/presentation.xml","<?xml version=\"1.0\"?><p:presentation xmlns:p=\"$P\" xmlns:a=\"$A\" xmlns:r=\"$R\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"master\"/></p:sldMasterIdLst><p:sldIdLst>${slides.indices.joinToString(""){"<p:sldId id=\"${256+it}\" r:id=\"slide${it+1}\"/>"}}</p:sldIdLst><p:sldSz cx=\"${emu(deck.size.width)}\" cy=\"${emu(deck.size.height)}\" type=\"custom\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
                    part("ppt/_rels/presentation.xml.rels",relationships(rel("master","slideMaster","slideMasters/slideMaster1.xml")+slides.indices.joinToString(""){rel("slide${it+1}","slide","slides/slide${it+1}.xml")}))
                    part("ppt/slideMasters/slideMaster1.xml","<?xml version=\"1.0\"?><p:sldMaster xmlns:p=\"$P\" xmlns:a=\"$A\" xmlns:r=\"$R\"><p:cSld><p:spTree>$group</p:spTree></p:cSld>$colorMap<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"layout\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>")
                    part("ppt/slideMasters/_rels/slideMaster1.xml.rels",relationships(rel("layout","slideLayout","../slideLayouts/slideLayout1.xml")+rel("theme","theme","../theme/theme1.xml")))
                    part("ppt/slideLayouts/slideLayout1.xml","<?xml version=\"1.0\"?><p:sldLayout xmlns:p=\"$P\" xmlns:a=\"$A\" xmlns:r=\"$R\" type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$group</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
                    part("ppt/slideLayouts/_rels/slideLayout1.xml.rels",relationships(rel("master","slideMaster","../slideMasters/slideMaster1.xml")))
                    part("ppt/theme/theme1.xml",theme())
                    slides.forEachIndexed{i,s->part("ppt/slides/slide${i+1}.xml",s.first);part("ppt/slides/_rels/slide${i+1}.xml.rels",s.second)}
                    media.forEachIndexed{i,m->
                        check();zip.putNextEntry(ZipEntry("ppt/media/image${i+1}.jpg").apply{time=315532800000L});val md=MessageDigest.getInstance("SHA-256");var count=0L
                        m.file.inputStream().use{input->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;count+=n;require(count<=24L*1024*1024);md.update(b,0,n);zip.write(b,0,n)}}
                        require(md.digest().joinToString(""){"%02x".format(it)}==m.digest);zip.closeEntry()
                    }
                }
            }
            check();FileOutputStream(output,true).use{it.fd.sync()};require(output.length() in 1..PPTX_MAX);accepted=true
        }finally{if(!accepted)output.delete()}
    }
    private fun picture(id:Int,b:PptxRect,rel:String)="<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"$rel\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>${frame(b)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
    private fun shape(id:Int,item:PptxItem,b:PptxRect,leading:Float?):String {
        val geometry=when(item.kind){PptxKind.ELLIPSE->"ellipse";PptxKind.LINE->"line";else->"rect"}
        val text=item.kind==PptxKind.TEXT
        val body=if(!text)""else "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\"><a:normAutofit/></a:bodyPr><a:lstStyle/>${item.text.split('\n').joinToString(""){line->"<a:p><a:pPr algn=\"${item.align.xml}\"><a:lnSpc>${if(leading==null)"<a:spcPct val=\"100000\"/>"else"<a:spcPts val=\"${(leading*100).toInt()}\"/>"}</a:lnSpc><a:spcBef><a:spcPts val=\"0\"/></a:spcBef><a:spcAft><a:spcPts val=\"0\"/></a:spcAft></a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"${(item.font*100).toInt()}\" b=\"${if(item.bold)1 else 0}\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"${item.color}\"/></a:solidFill><a:latin typeface=\"${PptxGeometry.font(line)}\"/><a:ea typeface=\"Arial Unicode MS\"/><a:cs typeface=\"Arial\"/></a:rPr><a:t xml:space=\"preserve\">${xml(line)}</a:t></a:r><a:endParaRPr sz=\"${(item.font*100).toInt()}\"/></a:p>"}}</p:txBody>"
        val fill=if(text||item.kind==PptxKind.LINE)"<a:noFill/>"else"<a:solidFill><a:srgbClr val=\"${item.color}\"/></a:solidFill>"
        val stroke=if(text)"<a:ln><a:noFill/></a:ln>"else"<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"${item.color}\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"${if(text)"Text"else"Shape"} $id\"/><p:cNvSpPr${if(text)" txBox=\"1\""else""}/><p:nvPr/></p:nvSpPr><p:spPr>${frame(b)}<a:prstGeom prst=\"$geometry\"><a:avLst/></a:prstGeom>$fill$stroke</p:spPr>$body</p:sp>"
    }
    private fun theme():String {
        val colors=listOf("dk1" to "000000","lt1" to "FFFFFF","dk2" to "111827","lt2" to "F3F4F6","accent1" to "2563EB","accent2" to "16A34A","accent3" to "DC2626","accent4" to "FBBF24","accent5" to "9333EA","accent6" to "0891B2","hlink" to "0000FF","folHlink" to "800080")
        val fill="<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";val line="<a:ln w=\"12700\">$fill<a:prstDash val=\"solid\"/></a:ln>"
        return "<?xml version=\"1.0\"?><a:theme xmlns:a=\"$A\" name=\"RME\"><a:themeElements><a:clrScheme name=\"RME\">${colors.joinToString(""){(n,c)->"<a:$n><a:srgbClr val=\"$c\"/></a:$n>"}}</a:clrScheme><a:fontScheme name=\"RME\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"Arial Unicode MS\"/><a:cs typeface=\"Arial\"/></a:majorFont><a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"Arial Unicode MS\"/><a:cs typeface=\"Arial\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"RME\"><a:fillStyleLst>${fill.repeat(3)}</a:fillStyleLst><a:lnStyleLst>${line.repeat(3)}</a:lnStyleLst><a:effectStyleLst>${"<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3)}</a:effectStyleLst><a:bgFillStyleLst>${fill.repeat(3)}</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
    }
}
