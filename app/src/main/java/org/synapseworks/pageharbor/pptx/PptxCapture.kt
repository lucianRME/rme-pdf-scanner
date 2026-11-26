package org.synapseworks.pageharbor.pptx

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import org.synapseworks.pageharbor.docx.*
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.ocr.*

internal data class PptxCapture(val source:DocxCapture,val deck:PptxDeck,val rawOcr:OcrResult?) {
    override fun toString()="PptxCapture(<redacted>)"
}
/** Reuses immutable acquisition/current appearance and restricted native extraction from M32. */
internal suspend fun capturePptx(context:Context,root:File,session:DocumentSession,ocr:OcrResult?,allowed:()->Boolean):PptxCapture {
    require(session.pages.size in 1..10)
    require(ocr==null||ocr.pages.size<=10&&ocr.pages.sumOf{it.text.length.toLong()}<=131072)
    val raw=ocr?.copy(pages=ocr.pages.map{p->p.copy(layout=p.layout?.let{l->l.copy(lines=l.lines.map{it.copy(elements=it.elements.toList())},blocks=l.blocks.map{it.copy(lines=it.lines.map{line->line.copy(elements=line.elements.toList())})})})})
    val eligible=raw?.copy(pages=raw.pages.filter{it.layout==null||it.layout.rotationDegrees==0})
    val capture=captureDocx(context,root,session,eligible,allowed)
    try {
        val pages=capture.document.pages.mapIndexed{i,original->
            check(allowed());val file=File(capture.frozen.directory,"visual-$i.jpg");val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds);require(bounds.outWidth>0&&bounds.outHeight>0)
            val visual=PptxMedia(file,bounds.outWidth,bounds.outHeight,PptxWriter.digest(file))
            val fallback=raw?.pages?.singleOrNull{it.pageIndex==i&&it.error==null}
            val page=if(original.lines.isEmpty()&&!fallback?.text.isNullOrBlank()) {
                val lines=fallback!!.text.lines().mapIndexed{n,s->DocxLine(n,s,null,null,emptyList())};require(lines.size<=256)
                original.copy(sourceText=fallback.text,lines=lines,blocks=if(fallback.text.isBlank())emptyList()else listOf(DocxBlock(0,DocxKind.BODY,fallback.text,lines.filter{it.text.isNotBlank()}.map{it.id},null)))
            }else original
            val geometry=page.origin !in setOf(DocxOrigin.FREEFORM_OCR,DocxOrigin.MANUAL)&&page.lines.all{l->l.text.isBlank()||l.bounds?.let{b->b.left>=0&&b.top>=0&&b.right<=page.widthPx&&b.bottom<=page.heightPx&&b.right>b.left&&b.bottom>b.top}==true}&&kotlin.math.abs(page.widthPx.toFloat()/page.heightPx-visual.width.toFloat()/visual.height)<.03f
            val p=PptxPage(page,visual,emptyList(),session.pages[i].rotation.degrees,!session.pages[i].annotations.isEmpty,geometry)
            val height=PptxGeometry.page(p,PptxSize.WIDE).height
            p.copy(items=PptxGeometry.proposals(page,geometry).map{b->b.copy(font=((b.box?.height?:.05f)*height/b.text.lines().size*.8f).coerceIn(8f,48f))})
        }
        return PptxCapture(capture,PptxDeck(pages),raw)
    }catch(t:Throwable){capture.frozen.directory.deleteRecursively();throw t}
}
internal fun cropPptxPicture(c:PptxCapture,page:Int,box:DocxBox,check:()->Unit):Pair<PptxMedia,DocxBox> {
    val p=c.deck.pages[page];require(p.geometry&&p.source.lines.all{it.text.isBlank()||it.bounds!=null});require(box.width*box.height<=.8f)
    for(l in p.source.lines.filter{it.text.isNotBlank()}){val b=requireNotNull(l.bounds);require(!box.intersects(DocxBox(b.left/p.source.widthPx,b.top/p.source.heightPx,b.right/p.source.widthPx,b.bottom/p.source.heightPx)))}
    val pic=cropDocxPicture(c.source,page,box,check)
    // Match the cropper's integer source rectangle, preserving even its sub-pixel edge rounding.
    val w=p.visual.width;val h=p.visual.height
    val region=DocxBox((box.left*w).toInt().toFloat()/w,(box.top*h).toInt().toFloat()/h,(box.right*w).toInt().coerceAtMost(w).toFloat()/w,(box.bottom*h).toInt().coerceAtMost(h).toFloat()/h)
    return PptxMedia(pic.file,pic.width,pic.height,PptxWriter.digest(pic.file))to region
}
