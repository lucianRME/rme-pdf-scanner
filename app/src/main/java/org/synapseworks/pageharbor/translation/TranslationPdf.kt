package org.synapseworks.pageharbor.translation

import android.content.Context
import android.graphics.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.ocr.*

class TranslationPdfSnapshot(val text:String,val draftRevision:Long,val sourceRevision:Long,val references:List<Pair<Int,String>>){override fun toString()="TranslationPdfSnapshot(<redacted>)"}
class TranslationPdfPage(val lines:List<String>){override fun toString()="TranslationPdfPage(<redacted>)"}
object TranslationPdfPlan {
 const val WIDTH=1190;const val HEIGHT=1684;const val MAX_PAGES=12;const val PDF_LIMIT=16*1024*1024
 fun validate(text:String){TranslationUtf8.encode(text);require(text.isNotBlank());var i=0;while(i<text.length){val c=text[i].code
  require(c==10 || c==13 && i+1<text.length&&text[i+1]=='\n' || c in 32..126 || c in 160..172 || c in 174..591 || c==0x3bc || c==0x39c || c in listOf(0x2013,0x2014,0x2018,0x2019,0x201c,0x201d,0x2026,0x20ac)){"PDF_UNSUPPORTED_CHARACTER"};i++}}
 fun pages(text:String,measure:(String)->Float):List<TranslationPdfPage>{validate(text);val out=mutableListOf<String>();val source=text.replace("\r\n","\n")
  for(paragraph in source.split('\n')){if(paragraph.isEmpty()){out+="";continue};var start=0
   while(start<paragraph.length){var end=start;while(end<paragraph.length&&measure(paragraph.substring(start,end+1))<=1046f)end++
    require(end>start){"PDF_WORD_DOES_NOT_FIT"};if(end<paragraph.length){val space=paragraph.lastIndexOf(' ',end-1);require(space>=start){"PDF_WORD_DOES_NOT_FIT"};end=space+1}
    out+=paragraph.substring(start,end);start=end;require(out.size<=35*MAX_PAGES){"PDF_PAGE_LIMIT"}
   };require(out.size<=35*MAX_PAGES){"PDF_PAGE_LIMIT"}
  };require(out.size<=35*MAX_PAGES){"PDF_PAGE_LIMIT"};return out.chunked(35).map{TranslationPdfPage(it)}
 }
}

/** Both appearance and synthetic Unicode layout derive exclusively from this frozen reviewed snapshot. */
object TranslationPdf {
 suspend fun create(context:Context,s:TranslationPdfSnapshot,assistance:Boolean=false,check:()->Unit={}):Pair<ByteArray,Int> = withContext(Dispatchers.IO){
  TranslationPdfPlan.validate(s.text);require(s.references.size in 1..3);require(s.references.all{it.first in 1..3&&it.second.matches(Regex("[A-Za-z0-9_-]{1,64}"))})
  val body=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;typeface=Typeface.MONOSPACE;textSize=26f};val small=Paint(body).apply{textSize=18f}
  s.text.filterNot{it=='\n'||it=='\r'||it==' '||it=='\u00a0'}.forEach{require(body.hasGlyph(it.toString())){"PDF_UNSUPPORTED_GLYPH"}}
  val pages=TranslationPdfPlan.pages(s.text,body::measureText);require(body.fontMetrics.bottom-body.fontMetrics.top<=36f)
  val header="Draft revision: ${s.draftRevision}. Captured source revision: ${s.sourceRevision}. Input references: "+s.references.joinToString("; "){"page ${it.first} [${it.second}]"}+". Freely edited draft; no paragraph-level source attribution."
  require(header.toByteArray().size<=512);val headerLines=TranslationPdfPlan.pages(header,small::measureText).flatMap{it.lines};require(headerLines.size<=7)
  val root=File(context.cacheDir,"shared-pdfs").apply{mkdirs()};val dir=File(root,"translation-work-${UUID.randomUUID()}");require(dir.mkdir());val output=File(dir,"Translation.pdf");var jpegBytes=0L
  try{
   val requests=pages.mapIndexed{index,page->coroutineContext.ensureActive();check();val bitmap=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888);val lines=mutableListOf<OcrTextLine>();val jpeg=File(dir,"page-$index.jpg")
    try{val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
     fun draw(text:String,top:Float,paint:Paint){val baseline=top-paint.fontMetrics.top;val bottom=baseline+paint.fontMetrics.bottom;val right=72f+paint.measureText(text);require(right<=1118.01f&&bottom<=1612f);canvas.drawText(text,72f,baseline,paint);if(text.isNotBlank())lines+=OcrTextLine(text,OcrTextBounds(72f,top,right,bottom))}
     draw(if(assistance)"Reviewed extractive assistance draft" else "Reflowed translated draft",72f,small);headerLines.forEachIndexed{i,line->draw(line,100f+i*24f,small)}
     page.lines.forEachIndexed{i,line->check();draw(line,300f+i*36f,body)};draw("Output page ${index+1} of ${pages.size}",1588f,small)
     jpeg.outputStream().use{require(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))};require(jpeg.length()<=1024*1024);jpegBytes+=jpeg.length();require(jpegBytes<=12*1024*1024)
    }finally{bitmap.recycle()}
    SearchablePdfPage({jpeg.inputStream()},OcrPageResult(index,lines.joinToString("\n"){it.text},layout=OcrPageLayout(1190,1684,lines=lines)),PdfPageSize(595f,842f))
   }
   coroutineContext.ensureActive();check();val result=PdfBoxSearchablePdfGenerator(context).generate(SearchablePdfRequest(requests,output,dir,standardInvisibleGlyphs=true));require(result is SearchablePdfGenerationResult.Success&&result.textLayerPageCount==pages.size){"PDF_GENERATION_FAILED"};check();require(output.length() in 1..TranslationPdfPlan.PDF_LIMIT.toLong());require(dir.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=64L*1024*1024);val bytes=output.readBytes();coroutineContext.ensureActive();check();bytes to pages.size
  }finally{dir.deleteRecursively()}
 }
}
