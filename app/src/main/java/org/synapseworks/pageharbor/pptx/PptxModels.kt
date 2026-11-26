package org.synapseworks.pageharbor.pptx

import java.io.File
import org.synapseworks.pageharbor.docx.*

internal const val PPTX_MIME="application/vnd.openxmlformats-officedocument.presentationml.presentation"
internal const val PPTX_MAX=32L*1024*1024
internal enum class PptxMode(val label:String){IMAGE("Page-image slides"),EDITABLE("Editable slides")}
internal enum class PptxSize(val width:Float,val height:Float){WIDE(960f,540f),STANDARD(720f,540f),PORTRAIT(540f,720f)}
internal enum class PptxKind{TEXT,IMAGE,RECT,ELLIPSE,LINE}
internal enum class PptxAlign(val xml:String){LEFT("l"),CENTER("ctr"),RIGHT("r")}
internal data class PptxMedia(val file:File,val width:Int,val height:Int,val digest:String){override fun toString()="PptxMedia(<redacted>)"}
internal data class PptxItem(val id:Int,val kind:PptxKind,val box:DocxBox?,val text:String="",val sourceIds:List<Int> = emptyList(),
    val font:Float=18f,val bold:Boolean=false,val align:PptxAlign=PptxAlign.LEFT,val color:String="000000",
    val media:PptxMedia?=null,val sourceRegion:DocxBox?=null,val corrected:Boolean=false) {
    override fun toString()="PptxItem(<redacted>)"
}
internal data class PptxPage(val source:DocxPage,val visual:PptxMedia,val items:List<PptxItem>,val rotation:Int=0,val marked:Boolean=false,
    val geometry:Boolean=true,val omitted:Set<Int> = emptySet(),val coverage:Boolean=false) {
    fun owned()=copy(source=source.owned(),items=items.map{it.copy(sourceIds=it.sourceIds.toList())},omitted=omitted.toSet())
    override fun toString()="PptxPage(<redacted>)"
}
internal data class PptxDeck(val pages:List<PptxPage>,val mode:PptxMode=PptxMode.IMAGE,val size:PptxSize=PptxSize.WIDE) {
    fun owned()=copy(pages=pages.map{it.owned()})
    fun problem(forPreview:Boolean=false):String? {
        if(pages.size !in 1..10||pages.sumOf{it.items.size}>512||pages.sumOf{it.items.sumOf{b->b.text.length}}>131072)return "Presentation exceeds page, object or text limits."
        if(pages.any{it.visual.width !in 1..6000||it.visual.height !in 1..6000||it.visual.width.toLong()*it.visual.height>12_500_000})return "Source image dimensions exceed the supported profile."
        if(mode==PptxMode.IMAGE)return null
        if(pages.any{it.marked})return "Editable annotation/signature transfer is unsupported. Choose page-image slides to preserve those marks."
        if(pages.any{it.rotation!=0})return "Rotated editable reconstruction is unsupported. Page-image slides preserve current rotation."
        if(pages.any{it.items.size !in (if(forPreview)0..128 else 1..128)||!forPreview&&it.items.none{b->b.kind!=PptxKind.IMAGE}})return "Every editable slide needs native text or shapes; an image-only slide is not editable conversion."
        if(!forPreview&&pages.any{!it.coverage})return "Review every page's artwork and explicit omissions. Automatic figure/shape discovery is not provided."
        if(pages.any{p->val ids=p.items.flatMap{it.sourceIds};ids.distinct().size!=ids.size||(ids.toSet()+p.omitted)!=p.source.lines.filter{it.text.isNotBlank()}.map{it.id}.toSet()})return "Source-line accounting is incomplete. Review every proposal or explicitly omit it."
        if(pages.any{p->p.items.map{it.id}.distinct().size!=p.items.size||p.items.any{it.id !in 0..1_000_000}})return "Object identities are invalid."
        if(pages.any{p->p.items.any{it.box==null}})return "Missing source geometry: place every object explicitly before export."
        if(pages.any{p->p.items.any{!it.color.matches(Regex("[0-9A-F]{6}"))||!it.font.isFinite()||it.font !in 8f..48f||!DocxText.safe(it.text)||'\r' in it.text||'\t' in it.text||!forPreview&&it.kind==PptxKind.TEXT&&it.text.isBlank()}})return "Unsupported text/script, font or style. Use the declared BMP LTR profile and LF newlines."
        val pictures=pages.flatMap{it.items}.filter{it.kind==PptxKind.IMAGE}
        if(pictures.size>20||pictures.sumOf{it.media?.file?.length()?:0}>20L*1024*1024)return "Picture budget exceeded."
        for(p in pages)for(item in p.items) {
            val box=item.box!!
            if(item.kind==PptxKind.IMAGE) {
                val m=item.media?:return "Selected image is missing."
                val region=item.sourceRegion?:return "An image needs its reviewed source region."
                if(!p.geometry||p.source.lines.any{it.text.isNotBlank()&&it.bounds==null})return "Text-free crop validation needs complete source geometry. Use image mode or text/shape-only reconstruction."
                if(region.width*region.height>.8f||p.source.lines.any{l->l.text.isNotBlank()&&l.bounds?.let{b->region.intersects(DocxBox(b.left/p.source.widthPx,b.top/p.source.heightPx,b.right/p.source.widthPx,b.bottom/p.source.heightPx))}==true})return "Image region intersects original text or resembles a whole page. Select text-free artwork."
                val ratio=box.width*p.visual.width/(box.height*p.visual.height)
                if(m.width<=0||m.height<=0||kotlin.math.abs(ratio/(m.width.toFloat()/m.height)-1)>.002f)return "Image placement must preserve its pixel aspect ratio."
            }
            if(item.kind!=PptxKind.TEXT&&p.items.any{b->b.kind==PptxKind.TEXT&&b.box?.intersects(box)==true})return "Artwork overlaps native text. Move or resize it before exporting."
        }
        return null
    }
    override fun toString()="PptxDeck(<redacted>)"
}
internal data class PptxRect(val x:Float,val y:Float,val width:Float,val height:Float)
internal object PptxGeometry {
    fun page(page:PptxPage,size:PptxSize):PptxRect {val s=minOf(size.width/page.visual.width,size.height/page.visual.height);val w=page.visual.width*s;val h=page.visual.height*s;return PptxRect((size.width-w)/2,(size.height-h)/2,w,h)}
    fun item(page:PptxPage,size:PptxSize,box:DocxBox):PptxRect {val p=page(page,size);return PptxRect(p.x+box.left*p.width,p.y+box.top*p.height,box.width*p.width,box.height*p.height)}
    fun proposals(source:DocxPage,geometry:Boolean):List<PptxItem> = source.blocks.map { b ->
        PptxItem(b.id,PptxKind.TEXT,if(geometry)b.box else null,b.text,b.sourceIds.toList(),18f,b.kind==DocxKind.HEADING)
    }
    /** Preserve observed baseline spacing only when the reviewed paragraphs still map one to one to positioned source lines. */
    fun leading(page:PptxPage,item:PptxItem,size:PptxSize):Float? {
        if(item.sourceIds.size<2||item.text.lines().size!=item.sourceIds.size||!page.geometry)return null
        val lines=item.sourceIds.map{id->page.source.lines.firstOrNull{it.id==id}?.bounds?:return null}
        val steps=lines.zipWithNext{a,b->b.top-a.top}.sorted()
        val value=steps[steps.size/2]/page.source.heightPx*page(page,size).height
        return value.takeIf{it.isFinite()&&it>=item.font&&it<=item.font*3f}
    }
    fun font(text:String)=if(text.any{Character.UnicodeScript.of(it.code)==Character.UnicodeScript.GREEK||it=='µ'})"Helvetica"else"Arial"
}
