package org.synapseworks.pageharbor.docx

import java.util.Base64
import org.synapseworks.pageharbor.ocr.*

/** Frozen independent TSV: source geometry/text, not writer-derived expected output. */
internal object FrozenDocxCorpus {
    val names=listOf("letter","columns","table","mixed","pages","ambiguous","rtl-indic","overflow","native")
    data class Row(val id:String,val page:Int,val block:String,val bounds:OcrTextBounds,val kind:DocxKind,val column:Int,val text:String)
    fun rows(tsv:String)=tsv.lineSequence().filter{it.isNotBlank()}.map { line -> val p=line.split('\t');require(p.size==10);Row(p[0],p[1].toInt(),p[2],OcrTextBounds(p[3].toFloat(),p[4].toFloat(),p[5].toFloat(),p[6].toFloat()),DocxKind.valueOf(p[7]),p[8].toInt(),String(Base64.getDecoder().decode(p[9]),Charsets.UTF_8)) }.toList()
    fun ocr(tsv:String,blocks:Boolean=true):List<OcrPageResult> = rows(tsv).groupBy{it.page}.toSortedMap().map { (page,rows) ->
        val lines=rows.map{OcrTextLine(it.text,it.bounds,1f)}
        val groups=if(blocks)rows.groupBy{it.block}.values.map{g->OcrTextBlock(g.map{r->lines[rows.indexOf(r)]})}else emptyList()
        OcrPageResult(page,lines.joinToString("\n"){it.text},layout=OcrPageLayout(1224,1584,lines=lines,blocks=groups))
    }
    fun tableRegion(name:String)=if(name=="table")DocxBox(36f/612,136f/792,396f/612,216f/792)else null
    fun imageRegion(name:String)=when(name){"letter"->DocxBox(36f/612,340f/792,156f/612,400f/792);"columns"->DocxBox(318f/612,180f/792,438f/612,240f/792);else->null}
}
