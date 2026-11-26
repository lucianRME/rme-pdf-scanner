package org.synapseworks.pageharbor.document

import android.app.Application
import android.graphics.*
import android.graphics.pdf.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.batch.*
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportViewModel
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ui.home.applyPreviewRotationAndFilter
import org.synapseworks.pageharbor.document.searchablepdf.*
import org.synapseworks.pageharbor.ocr.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class BackgroundWhiteningExportInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File;private lateinit var source:File;private lateinit var before:ByteArray;private lateinit var page:DocumentPage
    private val w=192;private val h=128;private val settings=ManualImageAdjustments(20,-25)
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/m09-export-${UUID.randomUUID()}").apply{check(mkdirs())};source=File(root,"source.png")
        val pixels=IntArray(w*h){i->val x=i%w;val y=i/w;val ink=y%24 in 6..9&&x%31 in 3..25;val gain=0.45f+0.55f*x/(w-1)
            fun c(v:Int):Int { return (v*gain).toInt() }
            0xff000000.toInt()or(c(if(ink)30 else 232)shl 16)or(c(if(ink)34 else 228)shl 8)or c(if(ink)40 else 219)}
        val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        try{source.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
        before=source.readBytes();page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
            imageMetadata=DocumentImageMetadata(source.length(),w,h),filter=DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=settings)
    }
    @After fun cleanup(){root.deleteRecursively()}
    private fun expected():IntArray {
        val original=BitmapFactory.decodeFile(source.path);val rendered=applyPreviewRotationAndFilter(original,page.rotation,page.filter,page.adjustments)
        return try{IntArray(w*h).also{rendered.getPixels(it,0,w,0,0,w,h)}}finally{if(rendered!==original)rendered.recycle();original.recycle()}
    }
    private fun assertNear(a:Int,b:Int){for(shift in listOf(0,8,16))assertTrue(kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255))<=3)}
    private fun rendered(file:File,password:String?=null):Int {
        val fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer=if(password==null)PdfRenderer(fd)else if(android.os.Build.VERSION.SDK_INT>=35)PdfRenderer(fd,LoadParams.Builder().setPassword(password).build())else error("Encrypted renderer unavailable")
        return renderer.use{it.openPage(0).use{p->val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);try{p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);b.getPixel(16,8)}finally{b.recycle()}}}
    }
    @Test fun previewFullAndSizedJpegUseExactlyOneSpatialThenManualPass() {
        val expected=expected();val outputs=mutableListOf<ByteArray>()
        assertEquals(settings.apply(-1),expected[16*w+16]) // real paper whitening, not a no-reference pass-through
        val full=ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({source.inputStream()},full,page.filter,imageMetadata=page.imageMetadata,adjustments=settings));outputs.add(full.toByteArray())
        val sized=ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeSizedJpegToDestination({source.inputStream()},sized,page.filter,page.rotation,page.imageMetadata,SizedJpegEncoding(w,h,100),adjustments=settings));outputs.add(sized.toByteArray())
        for(bytes in outputs){val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size);try{assertEquals(w,b.width);assertEquals(h,b.height);for((x,y)in listOf(16 to 8,160 to 8,16 to 16,160 to 16))assertNear(expected[y*w+x],b.getPixel(x,y))}finally{b.recycle()}}
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun normalAndTargetPdfUseTheReviewedVisualAndDiscardTheirTemporaryOutputs()=runBlocking {
        val pixel=expected()[8*w+16]
        val normal=recomposeNormalPdf(app,(normalPdfExportPlan(DocumentSession(listOf(page)))as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
        try{assertNear(pixel,rendered(normal.file))}finally{deleteNormalPdfRecomposition(normal.file)}
        assertFalse(normal.file.exists())
        val engine=LocalTargetSizePdfEngine(app);val result=engine.prepare(targetSizePdfRequest(DocumentSession(listOf(page)),targetBytes=512_000))as TargetSizePdfResult.TargetReached
        try{assertNear(pixel,rendered(result.prepared.file))}finally{engine.discard(result.prepared)}
        assertFalse(result.prepared.file.exists());assertArrayEquals(before,source.readBytes())
    }
    @Test fun realSearchablePdfRetainsTextAndRendersWhiteningBeforeManualExactlyOnce()=runBlocking {
        val text="Synthetic local text"
        val ocr=OcrResult(listOf(OcrPageResult(0,text,layout=OcrPageLayout(w,h,lines=
            listOf(OcrTextLine(text,OcrTextBounds(3f,6f,25f,10f)))))))
        var ocrCalls=0
        val coordinator=LocalSearchablePdfExportCoordinator(app,ocrEngine=object:OcrEngine {
            override fun recognize(pages:List<OcrPage>):OcrResult {
                ocrCalls++;assertArrayEquals(before,pages.single().openJpegStream().use{it.readBytes()});return ocr
            }
        })
        val result=coordinator.prepare(SearchablePdfExportRequest(
            pageUris=listOf(Uri.fromFile(source)),visualPages=listOf(SearchablePdfVisualPage(
                pageId=1L,originalUri=Uri.fromFile(source),filter=page.filter,adjustments=settings))))
        assertEquals(1,ocrCalls);assertTrue(result is SearchablePdfPreparedExport.Ready)
        val prepared=result as SearchablePdfPreparedExport.Ready
        try {
            PDDocument.load(prepared.temporaryFile).use{assertEquals(text,PDFTextStripper().getText(it).trim())}
            assertNear(expected()[8*w+16],rendered(prepared.temporaryFile))
        } finally {coordinator.discardPreparedExport(prepared)}
        assertFalse(prepared.temporaryFile.exists());assertArrayEquals(before,source.readBytes())
    }
    @Test fun savedAndRestoredThumbnailUseVersionedRecipeAndManualOnce() {
        val store=org.synapseworks.pageharbor.library.LibraryFileStore(app,File(root,"library"))
        val thumbnail=File(root,"thumb.jpg")
        assertTrue(store.createRestoreThumbnail(source,thumbnail,0,page.filter.name,settings.brightness,settings.contrast))
        val decoded=BitmapFactory.decodeFile(thumbnail.path)
        try {
            assertEquals(w,decoded.width);assertEquals(h,decoded.height)
            val expected=expected()
            for((x,y)in listOf(16 to 8,160 to 8,16 to 16,160 to 16))assertNear(expected[y*w+x],decoded.getPixel(x,y))
        } finally {decoded.recycle()}
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun cancelledAndFailingExportsCloseStreamsAndKeepOriginalBytes() {
        var checks=0;var closes=0;var opened=0;var inputCloses=0
        val output=object:ByteArrayOutputStream(){override fun close(){closes++;super.close()}}
        try {writeFilteredJpegToDestination({opened++;object:ByteArrayInputStream(before){override fun close(){inputCloses++;super.close()}}},output,page.filter,
            isCancellationRequested={++checks>50},adjustments=settings);fail()}catch(_:CancellationException){}
        assertEquals(0,output.size());assertEquals(1,closes);assertEquals(opened,inputCloses)
        var failedClosed=false;val denied=object:OutputStream(){override fun write(b:Int){throw IOException()};override fun write(b:ByteArray,off:Int,len:Int){throw IOException()};override fun close(){failedClosed=true}}
        assertEquals(PageExportResult.WriteFailed,writeFilteredJpegToDestination({source.inputStream()},denied,page.filter,adjustments=settings));assertTrue(failedClosed);assertArrayEquals(before,source.readBytes())
    }
    @Test @SdkSuppress(minSdkVersion=35)
    fun passwordCaptureStaysFrozenWhenASelectedBatchChangesWhiteningIntensity()=runBlocking {
        val model=withContext(Dispatchers.Main){PasswordPdfExportViewModel(app)};val owner=PageHarborSessionViewModel();var capturedRoot:File?=null
        try {
            withContext(Dispatchers.Main){owner.installDocumentSessionForTest(DocumentSession(listOf(page,page.copy(id=DocumentPageId(2)))));model.open(normalPdfExportPlan(owner.documentSession))}
            val capture=requireNotNull(model.state.source);capturedRoot=capture.parentFile;val capturedBytes=capture.readBytes();val pixel=expected()[8*w+16]
            withContext(Dispatchers.Main){owner.batchEditor.open();owner.batchEditor.select(setOf(1));owner.batchEditor.setEdit(BatchPageEdit(filter=DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,brightness=-50));owner.batchEditor.apply()}
            withTimeout(30_000){while(owner.batchEditor.state?.busy==true)delay(10)}
            assertEquals(BatchEditOutcome.APPLIED,owner.batchEditor.state?.outcome)
            val secret="synthetic-m09-key".toCharArray();withContext(Dispatchers.Main){model.prepare(secret)};assertTrue(secret.all{it=='\u0000'})
            withTimeout(30_000){while(model.state.busy)delay(10)}
            assertNear(pixel,rendered(requireNotNull(model.state.output),"synthetic-m09-key"));assertArrayEquals(capturedBytes,capture.readBytes());assertArrayEquals(before,source.readBytes())
        } finally {withContext(Dispatchers.Main){owner.batchEditor.close();model.close()};withTimeout(30_000){while(capturedRoot?.exists()==true)delay(10)}}
    }
}
