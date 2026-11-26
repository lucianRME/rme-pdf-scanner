package org.synapseworks.pageharbor.docx

import java.io.File
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.table.TableGrid

internal const val DOCX_MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
// Activated only after independent office layout gates pass.
internal const val DOCX_LAYOUT_AVAILABLE=true
internal enum class DocxMode { REFLOW, LAYOUT }
internal enum class DocxPaper(val width:Float,val height:Float) { LETTER(612f,792f),A4(595.2756f,841.8898f) }
internal enum class DocxKind { BODY,HEADING,BULLET,NUMBER,TABLE,IMAGE }
internal enum class DocxOrigin { POSITIONED_OCR,FREEFORM_OCR,NATIVE_PDF,MANUAL }
internal data class DocxBox(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init { require(listOf(left,top,right,bottom).all{it.isFinite()&&it in 0f..1f}&&right>left&&bottom>top) }
    val width get()=right-left;val height get()=bottom-top
    fun contains(x:Float,y:Float)=x in left..right&&y in top..bottom
    fun intersects(b:DocxBox)=left<b.right&&right>b.left&&top<b.bottom&&bottom>b.top
}
internal data class DocxLine(val id:Int,val text:String,val bounds:OcrTextBounds?,val confidence:Float?,val elements:List<OcrTextElement>) {
    override fun toString()="DocxLine(<redacted>)"
}
internal data class DocxPicture(val file:File,val width:Int,val height:Int) { override fun toString()="DocxPicture(<redacted>)" }
internal data class DocxBlock(val id:Int,val kind:DocxKind,val text:String,val sourceIds:List<Int>,val box:DocxBox?,val column:Int=0,
    val table:TableGrid?=null,val picture:DocxPicture?=null,val corrected:Boolean=false,val note:String?=null) {
    override fun toString()="DocxBlock(<redacted>)"
}
internal data class DocxPage(val widthPx:Int,val heightPx:Int,val sourceText:String,val lines:List<DocxLine>,val blocks:List<DocxBlock>,
    val origin:DocxOrigin,val columns:Int=1,val concerns:Set<String> = emptySet(),val omissions:Set<Int> = emptySet()) {
    fun owned()=copy(lines=lines.map{it.copy(elements=it.elements.toList())},blocks=blocks.map{it.copy(sourceIds=it.sourceIds.toList(),table=it.table?.owned())},omissions=omissions.toSet(),concerns=concerns.toSet())
    override fun toString()="DocxPage(<redacted>)"
}
internal data class DocxDocument(val pages:List<DocxPage>,val mode:DocxMode=DocxMode.REFLOW,val paper:DocxPaper=DocxPaper.LETTER) {
    fun owned()=copy(pages=pages.map{it.owned()})
    fun problem():String? {
        if(pages.size !in 1..10 || pages.sumOf{it.blocks.size}>512 || pages.sumOf{it.blocks.sumOf{b->b.text.length+(b.table?.rows?.flatten()?.sumOf{c->c.value.length}?:0)}}>131072)return "Document exceeds DOCX limits."
        if(pages.sumOf{p->p.blocks.sumOf{it.table?.rows?.sumOf{r->r.size}?:0}}>2048)return "DOCX table-cell resource limit exceeded."
        if(pages.any{p->p.blocks.none{it.text.isNotBlank()||it.table?.values()?.flatten()?.any{v->v.isNotBlank()}==true}})return "Every page needs editable text. Run OCR or transcribe; image-only DOCX is not provided."
        if(pages.any{p->p.blocks.isEmpty()||p.blocks.any{b->!DocxText.safe(b.text)||b.table?.rows?.flatten()?.any{!DocxText.safe(it.value)}==true}})return "Unsupported text or script. RTL/Indic/emoji support remains open; no source content was discarded."
        if(pages.any{p->val ids=p.blocks.flatMap{it.sourceIds};ids.distinct().size!=ids.size || (ids.toSet()+p.omissions)!=p.lines.filter{it.text.isNotBlank()}.map{it.id}.toSet()})return "Source-line accounting is incomplete. Review grouping and omissions."
        if(pages.any{it.blocks.any{b->b.kind==DocxKind.TABLE&&(b.table?.valid()!=true||b.table.rows.size>20||b.table.columns>6)}})return "Only rectangular tables up to 20 rows and 6 columns are supported."
        if(pages.any{p->p.blocks.any{it.kind==DocxKind.IMAGE&&it.picture==null}})return "A selected figure is unavailable."
        if(pages.sumOf{p->p.blocks.count{it.kind==DocxKind.IMAGE}}>20)return "Too many figures."
        if(pages.any{p->p.blocks.map{it.id}.distinct().size!=p.blocks.size||p.blocks.any{it.id !in 0..1_000_000}})return "Invalid block identity."
        if(mode==DocxMode.LAYOUT) {
            if(paper!=DocxPaper.LETTER)return "Measured layout supports Letter only. A4 layout remains open; use reflow."
            if(pages.any{p->p.blocks.any{b->b.box?.let{box->val x=if(p.columns==2&&b.column==1)318f else 36f;kotlin.math.abs(box.left*612-x)>3||box.width*612>(if(p.columns==2&&b.column>=0)258 else 540)+3}==true}})return "Layout needs aligned source columns and figures at the column start. Use reflow for floating or irregular content."
            if(pages.any{it.columns !in 1..2 || it.blocks.any{b->b.box==null}})return "Layout needs valid source geometry and one or two columns."
            if(pages.any{p->p.concerns.any{it.startsWith("LAYOUT:")}})return "Uncertain/overlapping layout needs correction or editable reflow."
            if(pages.any{p->p.columns==2&&p.blocks.any{it.kind==DocxKind.TABLE}})return "Measured two-column layout does not yet support source tables. Use reflow or a one-column page."
            if(pages.any{p->p.columns==2 && p.blocks.map{it.column}.zipWithNext().any{(a,b)->a>b}})return "Layout order must be spanning headings, left column, then right column. Review order or use reflow."
        }
        return null
    }
    override fun toString()="DocxDocument(<redacted>)"
}
internal object DocxText {
    private val scripts=setOf(Character.UnicodeScript.LATIN,Character.UnicodeScript.GREEK,Character.UnicodeScript.CYRILLIC,Character.UnicodeScript.HAN,Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA,Character.UnicodeScript.HANGUL,Character.UnicodeScript.COMMON,Character.UnicodeScript.INHERITED)
    fun safe(s:String):Boolean {
        if(s.length>32768)return false
        var i=0
        while(i<s.length) {
            val c=s[i++];val cp=if(Character.isHighSurrogate(c)){if(i>=s.length||!Character.isLowSurrogate(s[i]))return false;Character.toCodePoint(c,s[i++])}else{if(Character.isLowSurrogate(c))return false;c.code}
            if(cp in setOf(0xfffe,0xffff)||cp>0xffff||Character.isISOControl(cp)&&cp !in setOf(9,10,13)||Character.getType(cp)==Character.FORMAT.toInt()||Character.UnicodeScript.of(cp) !in scripts)return false
        };return true
    }
    fun xml(s:String):String { require(safe(s));return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;") }
    private val bullet=Regex("^\\s*(?:[•●‣]\\s*|[*\\-]\\s+)")
    fun isBullet(text:String)=bullet.containsMatchIn(text)
    fun isNumber(text:String)=Regex("^\\s*\\d{1,3}[.)]\\s+").containsMatchIn(text)
    fun content(b:DocxBlock):String=when(b.kind) {
        DocxKind.BULLET->b.text.replaceFirst(bullet,"")
        DocxKind.NUMBER->b.text.replaceFirst(Regex("^\\s*\\d{1,3}[.)]\\s+"),"")
        else->b.text
    }
}
