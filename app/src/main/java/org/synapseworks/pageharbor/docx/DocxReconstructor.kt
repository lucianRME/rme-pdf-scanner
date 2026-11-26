package org.synapseworks.pageharbor.docx

import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.table.*
import kotlin.math.*

/** Proposals preserve source strings/IDs/coordinates. They are never claimed as user corrections. */
internal object DocxReconstructor {
    private fun box(bounds:OcrTextBounds?,w:Int,h:Int):DocxBox?=bounds?.takeIf{it.left>=0&&it.top>=0&&it.right<=w&&it.bottom<=h&&it.right>it.left&&it.bottom>it.top}?.let{DocxBox(it.left/w,it.top/h,it.right/w,it.bottom/h)}
    // A size-only cutoff mistakes OCR cap-height variation for structure. Require a short,
    // separated singleton in the same horizontal text lane; sentences stay body proposals.
    private fun heading(group:List<DocxLine>,lines:List<DocxLine>,median:Float,width:Int):Boolean {
        if(group.size!=1)return false
        val line=group.single();val b=line.bounds?:return false
        val value=line.text.trim()
        if(b.bottom-b.top<median*1.25f||b.right-b.left>width*.8f||value.length>100||value.lastOrNull() in listOf('.', '!', '?', ';', ':'))return false
        val lane=lines.filter{other->other.id!=line.id&&other.bounds?.let{min(b.right,it.right)>max(b.left,it.left)}==true}
        val below=lane.mapNotNull{it.bounds}.filter{it.top>=b.bottom}.minOfOrNull{it.top-b.bottom}?:return false
        val above=lane.mapNotNull{it.bounds}.filter{it.bottom<=b.top}.minOfOrNull{b.top-it.bottom}
        return below>=median*.8f&&(above==null||above>=median*.6f)
    }
    fun page(ocr:OcrPageResult,origin:DocxOrigin=if(ocr.layout==null||ocr.layout.placementMode==OcrTextPlacementMode.FREEFORM_PAGE_REGION)DocxOrigin.FREEFORM_OCR else DocxOrigin.POSITIONED_OCR,check:()->Unit={}):DocxPage {
        require(ocr.error==null&&ocr.text.length<=32768)
        val layout=ocr.layout;require(layout==null||layout.rotationDegrees==0)
        val source=layout?.lines?:ocr.text.lines().map{OcrTextLine(it)};require(source.size<=256&&source.sumOf{it.text.length.toLong()}<=32768&&source.sumOf{it.elements.size}<=2048&&source.sumOf{it.elements.sumOf{e->e.text.length.toLong()}}<=32768)
        require(layout==null||layout.blocks.size<=256&&layout.blocks.sumOf{it.lines.size.toLong()}<=512)
        require(ocr.text.filterNot{it.isWhitespace()}.groupingBy{it}.eachCount()==source.joinToString(""){it.text}.filterNot{it.isWhitespace()}.groupingBy{it}.eachCount()){ "Inconsistent OCR text model" }
        val w=layout?.imageWidthPx?:612;val h=layout?.imageHeightPx?:792;require(w in 1..6000&&h in 1..6000&&w.toLong()*h<=12_500_000)
        val lines=source.mapIndexed{i,l->DocxLine(i,l.text,l.bounds,l.confidence,l.elements.toList())}
        val concerns=linkedSetOf("Reading order, grouping, headings and lists are proposals. Review every block.","Figures and tables require explicit region selection; automatic discovery remains open.")
        if(origin==DocxOrigin.FREEFORM_OCR)concerns+="LAYOUT: freeform correction has no faithful line geometry. Review grouping and use reflow."
        if(lines.any{box(it.bounds,w,h)==null})concerns+="LAYOUT: source geometry unavailable or outside the page."
        if(lines.any{it.confidence!=null&&it.confidence<.8f})concerns+="Low-confidence OCR. Correct the source text before export."
        val used=mutableSetOf<Int>();val groups=mutableListOf<List<DocxLine>>()
        layout?.blocks?.forEach { block ->
            check();val ids=block.lines.mapNotNull{l->source.indices.firstOrNull{it !in used&&source[it]==l}}
            if(ids.size==block.lines.size && ids.isNotEmpty()){used+=ids;groups+=ids.map{lines[it]}}
        }
        // Native runs / flat OCR: conservatively join close aligned continuations, not cross-column rows.
        for(line in lines.filter{it.id !in used&&it.text.isNotBlank()}.sortedWith(compareBy<DocxLine>{it.bounds?.top?:it.id.toFloat()}.thenBy{it.bounds?.left?:0f})) {
            check();val prior=groups.lastOrNull();val a=prior?.lastOrNull()?.bounds;val b=line.bounds
            val isList=DocxText.isBullet(line.text)||DocxText.isNumber(line.text)
            if(a!=null&&b!=null&&b.top>=a.bottom-1&&b.top-a.bottom<=max(2f,(a.bottom-a.top)*.65f)&&abs((a.bottom-a.top)-(b.bottom-b.top))<=max(1f,(a.bottom-a.top)*.2f)&&abs(a.left-b.left)<=max(2f,(a.bottom-a.top)*.4f)&&!isList)groups[groups.lastIndex]=prior+line
            else groups+=listOf(line)
        }
        val heights=lines.mapNotNull{it.bounds?.let{b->b.bottom-b.top}}.filter{it>0}.sorted();val median=heights.getOrNull(heights.size/2)?:14f
        val blocks=groups.filter{g->g.any{it.text.isNotBlank()}}.mapIndexed { index,g ->
            check();val text=g.joinToString("\n"){it.text};val bs=g.mapNotNull{box(it.bounds,w,h)}
            val region=if(bs.size==g.size)DocxBox(bs.minOf{it.left},bs.minOf{it.top},bs.maxOf{it.right},bs.maxOf{it.bottom})else null
            val kind=when {DocxText.isBullet(text)->DocxKind.BULLET;DocxText.isNumber(text)->DocxKind.NUMBER;origin!=DocxOrigin.FREEFORM_OCR&&heading(g,lines,median,w)->DocxKind.HEADING;else->DocxKind.BODY}
            DocxBlock(index,kind,text,g.map{it.id},region)
        }
        val left=blocks.filter{it.box?.let{b->b.left<.48f&&b.right<=.52f}==true&&it.kind!=DocxKind.HEADING};val right=blocks.filter{it.box?.left?.let{x->x>=.48f}==true}
        val columns=if(left.size>=2&&right.size>=2)2 else 1
        val assigned=blocks.map { b -> b.copy(column=if(columns==1)0 else when {b.kind==DocxKind.HEADING&&b.box?.top?.let{it<(left+right).minOf{x->x.box!!.top}}==true->-1;b.box?.left?.let{it>=.48f}==true->1;else->0}) }
        val ordered=assigned.sortedWith(compareBy<DocxBlock>{it.column}.thenBy{it.box?.top?:it.id.toFloat()}.thenBy{it.box?.left?:0f})
        for((i,a)in ordered.withIndex())for(b in ordered.take(i))if(a.box?.let{b.box?.intersects(it)}==true)concerns+="LAYOUT: overlapping source blocks. Use reflow or correct the grouping."
        return DocxPage(w,h,ocr.text,lines,ordered,origin,columns,concerns)
    }
    fun table(page:DocxPage,region:DocxBox,check:()->Unit={}):DocxPage {
        require(page.origin!=DocxOrigin.FREEFORM_OCR)
        val selected=page.lines.filter{l->box(l.bounds,page.widthPx,page.heightPx)?.let{region.contains((it.left+it.right)/2,(it.top+it.bottom)/2)}==true};require(selected.isNotEmpty())
        val ids=selected.map{it.id}.toSet();require(page.blocks.none{b->b.sourceIds.any{it in ids}&&b.sourceIds.any{it !in ids}})
        require(page.blocks.filter{b->b.sourceIds.any{it in ids}}.none{b->if(b.kind==DocxKind.TABLE)b.corrected||b.table?.rows?.flatten()?.any{it.corrected}==true else b.text!=b.sourceIds.joinToString("\n"){id->page.lines.first{it.id==id}.text}})
        val layout=OcrPageLayout(page.widthPx,page.heightPx,lines=selected.map{OcrTextLine(it.text,it.bounds,it.confidence,it.elements)})
        val extraction=requireNotNull(TableParser.parse(selected.joinToString("\n"){it.text},layout,check));require(extraction.grid.rows.size<=20&&extraction.grid.columns in 2..6)
        val index=page.blocks.indexOfFirst{b->b.sourceIds.any{it in ids}};require(index>=0)
        val block=DocxBlock((page.blocks.maxOfOrNull{it.id}?:0)+1,DocxKind.TABLE,"",ids.toList(),region,page.blocks[index].column,extraction.grid.owned(),note="Region-assisted M22 grid proposal. Verify rows, columns and every cell.")
        val blocks=page.blocks.filterNot{b->b.sourceIds.any{it in ids}}.toMutableList();blocks.add(index.coerceAtMost(blocks.size),block)
        return page.copy(blocks=blocks,concerns=page.concerns+extraction.concerns.map{it.message})
    }
}
