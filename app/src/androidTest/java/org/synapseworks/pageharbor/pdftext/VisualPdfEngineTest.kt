package org.synapseworks.pageharbor.pdftext

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.*
import org.junit.Assert.*
import java.io.File
import org.json.JSONObject
import org.synapseworks.pageharbor.forms.*
import org.synapseworks.pageharbor.document.annotation.AnnotationBounds

class VisualPdfEngineTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext;private lateinit var dir:File;private lateinit var scratch:File
    private val expected get()=JSONObject(i.context.assets.open("m24visual/expected.json").bufferedReader().use{it.readText()})
    @Before fun setup(){PDFBoxResourceLoader.init(app);dir=File(app.cacheDir,"m24-visual-${java.util.UUID.randomUUID()}").apply{mkdirs()};scratch=File(dir,"scratch").apply{mkdirs()}}
    @After fun cleanup(){assertTrue(dir.deleteRecursively())}
    private fun fixture(n:String)=File(dir,n).apply{writeBytes(i.context.assets.open("m24visual/$n").use{it.readBytes()})}
    @Test fun visualAdmissionDoesNotMakeNativeIneligiblePagesNativelyEditable(){for(name in listOf("image.pdf","courier.pdf")){val f=fixture(name);val bytes=f.readBytes();assertEquals(1,VisualPdfEngine.inspect(f,scratch).pages.size);try{PdfTextEngine.inspect(f,scratch);fail()}catch(_:FormException){};assertArrayEquals(bytes,f.readBytes())}}
    @Test fun allFrozenSupportedProfilesProduceVisibleCopiesAndRetainCoveredText(){val names=expected.getJSONArray("supported");for(n in 0 until names.length()){
        val name=names.getString(n);val f=fixture(name);val bytes=f.readBytes();val m=VisualPdfEngine.inspect(f,scratch);val edit=VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020");val bitmap=VisualPdfEngine.preview(f,0);assertEquals(m.pages[0].displayWidth/m.pages[0].displayHeight,bitmap.width.toFloat()/bitmap.height,.01f);bitmap.recycle();val out=File(dir,"visual-$name");VisualPdfEngine.prepare(f,out,scratch,m,edit);assertEquals(m,VisualPdfEngine.inspect(out,scratch));assertArrayEquals(bytes,f.readBytes())
        PDDocument.load(out).use{pdf->val text=PDFTextStripper().getText(pdf).filterNot{it.isWhitespace()};assertTrue(text.contains("Zoë0020"));if(name.startsWith("courier"))assertTrue(text.contains("COVERED0012"))}
        File(app.cacheDir,"m24-visual-$name").writeBytes(out.readBytes())
    }}
    @Test fun coloredBordersUnsupportedTextAndFitDeclineBeforeAnOutputExists(){val color=fixture("colored.pdf");val m=VisualPdfEngine.inspect(color,scratch);val out=File(dir,"color.pdf");try{VisualPdfEngine.prepare(color,out,scratch,m,VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020"));fail()}catch(_:FormException){};assertFalse(out.exists())
        val f=fixture("image.pdf");val model=VisualPdfEngine.inspect(f,scratch);val invalid=expected.getJSONArray("invalidText");for(n in 0 until invalid.length()){assertFalse(VisualPdfEngine.fits(model,VisualEdit(0,DEFAULT_VISUAL_REGION,invalid.getString(n))))};assertFalse(VisualPdfEngine.fits(model,VisualEdit(0,AnnotationBounds(.1f,.4f,.2f,.43f),"Zoë 0020")))
    }
    @Test fun unsafeSourcesAreNeverEnabledAndOriginalBytesStayExact(){val names=expected.getJSONArray("declined");for(n in 0 until names.length()){val f=fixture(names.getString(n));val before=f.readBytes();try{VisualPdfEngine.inspect(f,scratch);fail()}catch(_:Exception){};assertArrayEquals(before,f.readBytes())};val form=File(dir,"form.pdf").apply{writeBytes(i.context.assets.open("m23/compatible.pdf").use{it.readBytes()})};try{VisualPdfEngine.inspect(form,scratch);fail()}catch(_:FormException){}}
    @Test fun cancellationStopsInspectionAndPreparationWithoutChangingSource(){val f=fixture("image.pdf");val bytes=f.readBytes();var checks=0;try{VisualPdfEngine.inspect(f,scratch){if(++checks==10)throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){};val m=VisualPdfEngine.inspect(f,scratch);try{VisualPdfEngine.prepare(f,File(dir,"cancel.pdf"),scratch,m,VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020")){throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){};assertArrayEquals(bytes,f.readBytes())}
    @Test fun selectedSecondPageAndReusedDecodedBudgetAreBounded(){val f=fixture("mixed-two-pages.pdf");val m=VisualPdfEngine.inspect(f,scratch);assertEquals(2,m.pages.size);val out=File(dir,"mixed.pdf");VisualPdfEngine.prepare(f,out,scratch,m,VisualEdit(1,DEFAULT_VISUAL_REGION,"Zoë 0020"));File(app.cacheDir,"m24-visual-mixed-two-pages.pdf").writeBytes(out.readBytes())
        val budget=File(dir,"budget.pdf").apply{writeBytes(i.context.assets.open("m24/decoded-budget.pdf").use{it.readBytes()})};try{VisualPdfEngine.inspect(budget,scratch);fail()}catch(e:FormException){assertEquals(FormFailure.LIMIT,e.reason)}}
    @Test fun passiveLatinOcrLayerIsRetainedRatherThanCorrected(){val f=fixture("image-ocr.pdf");val m=VisualPdfEngine.inspect(f,scratch);val out=File(dir,"ocr.pdf");VisualPdfEngine.prepare(f,out,scratch,m,VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020"));PDDocument.load(out).use{val text=PDFTextStripper().getText(it);assertTrue(text.contains("HIDDEN OCR 0012"));assertTrue(text.contains("Zoë 0020"))};File(app.cacheDir,"m24-visual-image-ocr.pdf").writeBytes(out.readBytes())}
    @Test fun visualOutputUsesAliasAndAtomicSourceReplacementProtections(){val f=fixture("image.pdf");val bytes=f.readBytes();kotlinx.coroutines.runBlocking{FormWork.run{
        val source=FormHandle.capture(app,android.net.Uri.fromFile(f)){};try{val m=VisualPdfEngine.inspect(source.input,source.scratch);val out=source.output();VisualPdfEngine.prepare(source.input,out,source.scratch,m,VisualEdit(0,DEFAULT_VISUAL_REGION,"Zoë 0020"))
            try{FormDestination.write(source,out,android.net.Uri.parse("content://synthetic/visual-alias"),{android.os.ParcelFileDescriptor.open(f,android.os.ParcelFileDescriptor.MODE_READ_WRITE)}){};fail()}catch(e:FormException){assertEquals(FormFailure.DESTINATION,e.reason)};assertArrayEquals(bytes,f.readBytes())
            val retired=File(dir,"retired.pdf");assertTrue(f.renameTo(retired));f.writeText("REPLACED");var opened=false;try{FormDestination.write(source,out,android.net.Uri.parse("content://synthetic/visual-target"),{opened=true;null}){};fail()}catch(e:FormException){assertEquals(FormFailure.SOURCE_CHANGED,e.reason)};assertFalse(opened);assertEquals("REPLACED",f.readText());assertArrayEquals(bytes,retired.readBytes())
        }finally{source.close()}
    }}}
}
