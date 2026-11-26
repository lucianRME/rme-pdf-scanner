package org.synapseworks.pageharbor.docx

import android.content.Context
import android.net.Uri
import android.graphics.*
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.forms.*
import org.synapseworks.pageharbor.ocr.*
import org.synapseworks.pageharbor.pdftext.PdfTextEngine
import org.synapseworks.pageharbor.workflow.*

internal data class DocxCapture(val frozen:WorkflowCaptureResult,val document:DocxDocument,val nativeReason:String,val requiresAppearance:Boolean=false) {
    override fun toString()="DocxCapture(<redacted>)"
}
/** Reuse the validated current-appearance materializer, including EXIF/rotation/opaque sRGB once. */
internal suspend fun captureDocx(context:Context,root:File,session:DocumentSession,ocr:OcrResult?,allowed:()->Boolean):DocxCapture {
    require(session.pages.size in 1..10)
    val operation=coroutineContext
    val sourceCheck:()->Unit={operation.ensureActive();check(allowed())}
    val nativeUri=session.directPdfSource?.takeIf{session.canUseDirectPdf}?.toAndroidUri()
    val early=nativeUri?.let{File(root,"native-${java.util.UUID.randomUUID()}.pdf")}
    var earlyGuard:WorkflowSourceGuard?=null
    var earlyDigest:Pair<String,Long>?=null
    val result=try {
        if(nativeUri!=null&&early!=null){
            sourceCheck();earlyGuard=workflowSourceGuard(context,nativeUri)
            requireNotNull(context.contentResolver.openInputStream(nativeUri)).use{input->early.outputStream().use{out->val b=ByteArray(16384);var count=0L;while(true){sourceCheck();val n=input.read(b);if(n<0)break;require(n>0);count+=n;require(count<=16L*1024*1024);out.write(b,0,n)}}}
            earlyDigest=workflowDigest(early.inputStream(),16L*1024*1024,sourceCheck)
        }
        captureWorkflow(context,root,session,WorkflowPreset.ARCHIVAL,null,charArrayOf(),allowed)
    }catch(t:Throwable){early?.delete();throw t}
    var retained=false
    try {
        val current=coroutineContext;val check:()->Unit={current.ensureActive();check(allowed())}
        val visuals=session.pages.indices.map{File(result.directory,"visual-$it.jpg")}
        var native:List<OcrPageResult>?=null
        if(nativeUri!=null&&early!=null) {
            check();require(result.sourceGuards.any{it==earlyGuard}&&workflowSourceGuard(context,nativeUri)==earlyGuard)
            require(workflowDigest(requireNotNull(context.contentResolver.openInputStream(nativeUri)),16L*1024*1024,check)==earlyDigest)
            val source=File(result.directory,"native-input.pdf");require(early.renameTo(source))
            try {
                val scratch=File(result.directory,"native-scratch").apply{check(mkdir())}
                val inspected=PdfTextEngine.inspect(source,scratch,check);require(inspected.pages==session.pages.size)
                native=FormPdfEngine.load(source,scratch,FormBudget(check)).use{pdf->
                    (0 until pdf.numberOfPages).map { pageIndex ->
                        check();val page=pdf.getPage(pageIndex);val box=page.cropBox;val size=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(visuals[pageIndex].path,size);require(size.outWidth>0&&size.outHeight>0)
                        val lines=inspected.runs.filter{it.page==pageIndex+1}.map { run ->
                            val x=(run.x-box.lowerLeftX)/box.width*size.outWidth;val y=(box.upperRightY-run.y-run.size)/box.height*size.outHeight
                            OcrTextLine(run.text,OcrTextBounds(x,y,x+run.width/box.width*size.outWidth,y+run.size/box.height*size.outHeight),1f)
                        }
                        OcrPageResult(pageIndex,lines.joinToString("\n"){it.text},layout=OcrPageLayout(size.outWidth,size.outHeight,lines=lines))
                    }
                }
            } catch(e:CancellationException){throw e}catch(_:Exception){native=null}
        }
        val pages=session.pages.indices.map { i ->
            check();val text=native?.getOrNull(i)?:ocr?.pages?.singleOrNull{it.pageIndex==i&&it.error==null}
            val page=if(text!=null)DocxReconstructor.page(text,if(native!=null)DocxOrigin.NATIVE_PDF else if(text.layout==null||text.layout.placementMode==OcrTextPlacementMode.FREEFORM_PAGE_REGION)DocxOrigin.FREEFORM_OCR else DocxOrigin.POSITIONED_OCR,check)
                else DocxPage(612,792,"",emptyList(),emptyList(),DocxOrigin.MANUAL,concerns=setOf("No eligible native text or current OCR. Run OCR or explicitly transcribe and structure this page.","LAYOUT: no source text geometry."))
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(visuals[i].path,options)
            val ratio=page.widthPx.toFloat()/page.heightPx;val rendered=options.outWidth.toFloat()/options.outHeight
            if(kotlin.math.abs(ratio-rendered)>.03f)page.copy(concerns=page.concerns+"LAYOUT: text geometry differs from current appearance; review text and use reflow.")else page
        }
        check();retained=true
        return DocxCapture(result,DocxDocument(pages),if(native!=null)"Native text from the existing restricted PDF grammar; source occurrences/coordinates retained."else "Text from available current/original-source OCR or manual transcription. Native text was unavailable/ineligible. Visual edits do not automatically rewrite OCR.",session.pages.any{!it.annotations.isEmpty})
    } finally {early?.delete();if(!retained)result.directory.deleteRecursively()}
}

internal fun cropDocxPicture(capture:DocxCapture,page:Int,box:DocxBox,check:()->Unit={}):DocxPicture {
    check();val file=File(capture.frozen.directory,"visual-$page.jpg");val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
    require(bounds.outWidth>0&&bounds.outHeight>0)
    val rect=Rect((box.left*bounds.outWidth).toInt(),(box.top*bounds.outHeight).toInt(),(box.right*bounds.outWidth).toInt().coerceAtMost(bounds.outWidth),(box.bottom*bounds.outHeight).toInt().coerceAtMost(bounds.outHeight));require(rect.width()>0&&rect.height()>0)
    var sample=1;while(rect.width()/sample>2048||rect.height()/sample>2048)sample*=2
    @Suppress("DEPRECATION") val decoder=BitmapRegionDecoder.newInstance(file.path,false)?:error("Figure unavailable")
    var bitmap:Bitmap?=null
    val out=File(capture.frozen.directory,"figure-${java.util.UUID.randomUUID()}.jpg")
    try {
        @Suppress("DEPRECATION") val decoded=decoder.decodeRegion(rect,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=sample})?:error("Figure unavailable")
        bitmap=decoded;check();require(decoded.width<=2048&&decoded.height<=2048)
        out.outputStream().use{raw->
            val bounded=object:java.io.FilterOutputStream(raw){var count=0L
                override fun write(b:Int){check();require(++count<=2L*1024*1024);this.out.write(b)}
                override fun write(b:ByteArray,offset:Int,length:Int){check();count+=length;require(count<=2L*1024*1024);this.out.write(b,offset,length)}
            }
            require(decoded.compress(Bitmap.CompressFormat.JPEG,90,bounded))
        };check();require(out.length() in 1..2L*1024*1024)
        return DocxPicture(out,decoded.width,decoded.height)
    } catch(e:Throwable){out.delete();throw e}finally{bitmap?.recycle();decoder.recycle()}
}
