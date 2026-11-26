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

class PdfTextEngineTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext
    private lateinit var dir:File;private lateinit var scratch:File
    private val expected get()=JSONObject(i.context.assets.open("m24/expected.json").bufferedReader().use{it.readText()})
    @Before fun setup(){PDFBoxResourceLoader.init(app);dir=File(app.cacheDir,"m24-engine-${java.util.UUID.randomUUID()}").apply{mkdirs()};scratch=File(dir,"scratch").apply{mkdirs()}}
    @After fun cleanup(){assertTrue(dir.deleteRecursively())}
    private fun fixture(name:String)=File(dir,name).apply{writeBytes(i.context.assets.open("m24/$name").use{it.readBytes()})}
    @Test fun repeatedAccentedRunsHaveDistinctExactOccurrenceIdentity(){val f=fixture("repeated.pdf");val bytes=f.readBytes();val m=PdfTextEngine.inspect(f,scratch);assertEquals(listOf("Café 0012","Café 0012","KEEP 0042"),m.runs.map{it.text});assertEquals(3,m.runs.map{it.id}.distinct().size);assertEquals(210f,m.runs[1].y);assertArrayEquals(bytes,f.readBytes())}
    @Test fun frozenNativeOutputsRetainAllOtherOccurrencesAndRenderWithinBounds(){val names=expected.getJSONArray("supported");for(index in 0 until names.length()){
        val name=names.getString(index);val f=fixture(name);val bytes=f.readBytes();val m=PdfTextEngine.inspect(f,scratch);val run=m.runs[1];val out=File(dir,"edited-$name");PdfTextEngine.prepare(f,out,scratch,m,run.id,"Zoë 0020")
        assertEquals(listOf("Café 0012","Zoë 0020","KEEP 0042"),PdfTextEngine.inspect(out,scratch).runs.map{it.text});File(app.cacheDir,"m24-$name").writeBytes(out.readBytes());PDDocument.load(out).use{val extracted=PDFTextStripper().getText(it);File(app.cacheDir,"m24-extract-$index.txt").writeText(extracted);assertTrue(extracted.filterNot{c->c.isWhitespace()}.contains("Zoë0020"))}
        val bitmap=textPreview(out,scratch,run){};assertTrue(bitmap.width<=1024&&bitmap.height<=1024);bitmap.recycle();assertArrayEquals(bytes,f.readBytes());File(app.cacheDir,"m24-$name").writeBytes(out.readBytes())
    }}
    @Test fun frozenUnsupportedProfilesAreDeclinedWithoutChangingSource(){val names=expected.getJSONArray("declined");for(index in 0 until names.length()){val f=fixture(names.getString(index));val before=f.readBytes();var declined=false;try{PdfTextEngine.inspect(f,scratch)}catch(_:Exception){declined=true};assertTrue("Frozen decline index $index",declined);assertArrayEquals(before,f.readBytes())}}
    @Test fun unsupportedGlyphsAndFitOverflowNeverProduceReviewedOutput(){val f=fixture("repeated.pdf");val m=PdfTextEngine.inspect(f,scratch);val invalid=expected.getJSONArray("invalidReplacements");for(index in 0 until invalid.length()){val out=File(dir,"bad-$index.pdf");try{PdfTextEngine.prepare(f,out,scratch,m,m.runs[1].id,invalid.getString(index));fail()}catch(e:FormException){assertEquals(FormFailure.FIT,e.reason)};assertFalse(out.exists())}}
    @Test fun unchangedReplacementCannotMasqueradeAsAVisibleEdit(){val f=fixture("repeated.pdf");val m=PdfTextEngine.inspect(f,scratch);val out=File(dir,"unchanged.pdf");try{PdfTextEngine.prepare(f,out,scratch,m,m.runs[1].id,m.runs[1].text);fail()}catch(e:FormException){assertEquals(FormFailure.FIT,e.reason)};assertFalse(out.exists())}
    @Test fun cancellationAndChangedExpectedSourceNeverMutateOriginal(){val f=fixture("repeated.pdf");val bytes=f.readBytes();var n=0;try{PdfTextEngine.inspect(f,scratch){if(++n==15)throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){};val m=PdfTextEngine.inspect(f,scratch);try{PdfTextEngine.prepare(f,File(dir,"stale.pdf"),scratch,m.copy(runs=m.runs.drop(1)),m.runs[1].id,"Zoë 0020");fail()}catch(e:FormException){assertEquals(FormFailure.SOURCE_CHANGED,e.reason)};assertArrayEquals(bytes,f.readBytes())}
    @Test fun sharedContentOnTwoPagesReplacesOnlySelectedPageOccurrence(){val f=fixture("shared-two-pages.pdf");val m=PdfTextEngine.inspect(f,scratch);assertEquals(6,m.runs.size);val out=File(dir,"two-pages.pdf");PdfTextEngine.prepare(f,out,scratch,m,m.runs[4].id,"Zoë 0020");assertEquals(listOf("Café 0012","Café 0012","KEEP 0042","Café 0012","Zoë 0020","KEEP 0042"),PdfTextEngine.inspect(out,scratch).runs.map{it.text});File(app.cacheDir,"m24-shared-two-pages.pdf").writeBytes(out.readBytes())}
    @Test fun actualImageKerningTransformsAndDecodedBudgetHaveFrozenDeclines(){for(name in listOf("actual-image-scan.pdf","transformed.pdf","kerning.pdf","decoded-budget.pdf","covered-text.pdf","partial-cover.pdf")){val f=fixture(name);val before=f.readBytes();try{PdfTextEngine.inspect(f,scratch);fail()}catch(e:FormException){if(name=="decoded-budget.pdf")assertEquals(FormFailure.LIMIT,e.reason)};assertArrayEquals(before,f.readBytes())}}
    @Test fun sourceAliasesAtomicReplacementAndWriteCancellationUseSharedProtections(){val f=fixture("repeated.pdf");val before=f.readBytes();FormWork.runBlockingForTextTest {
        val source=FormHandle.capture(app,android.net.Uri.fromFile(f)){};try{val m=PdfTextEngine.inspect(source.input,source.scratch);val out=source.output();PdfTextEngine.prepare(source.input,out,source.scratch,m,m.runs[1].id,"Zoë 0020")
            try{FormDestination.write(source,out,android.net.Uri.parse("content://synthetic/alias"),{android.os.ParcelFileDescriptor.open(f,android.os.ParcelFileDescriptor.MODE_READ_WRITE)}){};fail()}catch(e:FormException){assertEquals(FormFailure.DESTINATION,e.reason)}
            val target=File(dir,"cancel.pdf").apply{createNewFile()};try{FormDestination.write(source,out,android.net.Uri.parse("content://synthetic/new"),{android.os.ParcelFileDescriptor.open(target,android.os.ParcelFileDescriptor.MODE_READ_WRITE)}){throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){};assertEquals(0,target.length());assertArrayEquals(before,f.readBytes())
            val retired=File(dir,"retired.pdf");assertTrue(f.renameTo(retired));f.writeText("NEW SOURCE");var opened=false;try{FormDestination.write(source,out,android.net.Uri.parse("content://synthetic/new"),{opened=true;null}){};fail()}catch(e:FormException){assertEquals(FormFailure.SOURCE_CHANGED,e.reason)};assertFalse(opened);assertEquals("NEW SOURCE",f.readText());assertArrayEquals(before,retired.readBytes())
        }finally{source.close()}
    }}
}
private fun FormWork.runBlockingForTextTest(block:()->Unit)=kotlinx.coroutines.runBlocking{FormWork.run{block()}}
