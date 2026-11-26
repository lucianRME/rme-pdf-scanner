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

class FingerRemovalExportInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File;private lateinit var source:File;private lateinit var before:ByteArray;private lateinit var page:DocumentPage
    private val w=512;private val h=384;private val settings=ManualImageAdjustments(-10,15)
    private lateinit var removal:org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.Prepared
    private lateinit var inputSource:File;private lateinit var inputBytes:ByteArray
    @Before fun setup()=runBlocking {
        check(app.packageName.endsWith(".phase2test"));root=File(app.cacheDir,"shared-pdfs/m29-export-${UUID.randomUUID()}").apply{check(mkdirs())}
        val input=org.synapseworks.pageharbor.document.finger.FingerFixtures.source(root).copy(filter=DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,adjustments=settings)
        inputSource=File(Uri.parse(input.source.reference).path!!);inputBytes=inputSource.readBytes()
        removal=org.synapseworks.pageharbor.document.finger.FingerFixtures.prepare(app,DocumentSession(listOf(input)))
        page=removal.after.pages.single();source=File(page.source.ownedTemporaryFile!!.path);before=source.readBytes()
    }
    @After fun cleanup(){if(::inputBytes.isInitialized&&::inputSource.isInitialized)assertArrayEquals(inputBytes,inputSource.readBytes());if(::removal.isInitialized)removal.close();if(::root.isInitialized)root.deleteRecursively()}
    private fun expected():IntArray {
        val original=BitmapFactory.decodeFile(source.path);val rendered=applyPreviewRotationAndFilter(original,page.rotation,page.filter,page.adjustments)
        return try{IntArray(w*h).also{rendered.getPixels(it,0,w,0,0,w,h)}}finally{if(rendered!==original)rendered.recycle();original.recycle()}
    }
    private fun assertNear(a:Int,b:Int){for(shift in listOf(0,8,16))assertTrue(kotlin.math.abs((a ushr shift and 255)-(b ushr shift and 255))<=3)}
    private fun rendered(file:File,password:String?=null):Int {
        val fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer=if(password==null)PdfRenderer(fd)else if(android.os.Build.VERSION.SDK_INT>=35)PdfRenderer(fd,LoadParams.Builder().setPassword(password).build())else error("Encrypted renderer unavailable")
        return renderer.use{it.openPage(0).use{p->val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);try{p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);b.getPixel(353,290)}finally{b.recycle()}}}
    }
    @Test fun previewFullAndSizedJpegUseExactlyOneSpatialThenManualPass() {
        val expected=expected();val outputs=mutableListOf<ByteArray>()

        val full=ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({source.inputStream()},full,page.filter,imageMetadata=page.imageMetadata,adjustments=settings));outputs.add(full.toByteArray())
        val sized=ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeSizedJpegToDestination({source.inputStream()},sized,page.filter,page.rotation,page.imageMetadata,SizedJpegEncoding(w,h,100),adjustments=settings));outputs.add(sized.toByteArray())
        for(bytes in outputs){val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size);try{assertEquals(w,b.width);assertEquals(h,b.height);for((x,y)in listOf(16 to 8,160 to 8,16 to 16,160 to 16,353 to 290))assertNear(expected[y*w+x],b.getPixel(x,y))}finally{b.recycle()}}
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun normalAndTargetPdfUseTheReviewedVisualAndDiscardTheirTemporaryOutputs()=runBlocking {
        val expected=expected();val pixel=expected[290*w+353]
        val evidence=File(app.getExternalFilesDir(null),"m29-evidence").apply{mkdirs()}
        source.copyTo(File(evidence,"accepted.png"),true);inputSource.copyTo(File(evidence,"original.png"),true)
        val incoming=BitmapFactory.decodeFile(inputSource.path);val beforeAppearance=applyPreviewRotationAndFilter(incoming,page.rotation,page.filter,settings)
        try{File(evidence,"before-manual.png").outputStream().use{check(beforeAppearance.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{if(beforeAppearance!==incoming)beforeAppearance.recycle();incoming.recycle()}
        val reference=Bitmap.createBitmap(expected,w,h,Bitmap.Config.ARGB_8888)
        try{File(evidence,"expected-manual.png").outputStream().use{check(reference.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{reference.recycle()}
        val normal=recomposeNormalPdf(app,(normalPdfExportPlan(DocumentSession(listOf(page)))as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
        try{assertNear(pixel,rendered(normal.file));File(app.getExternalFilesDir(null),"m29-evidence").apply{mkdirs()}.let{normal.file.copyTo(File(it,"ordinary.pdf"),true)}}finally{deleteNormalPdfRecomposition(normal.file)}
        assertFalse(normal.file.exists())
        val engine=LocalTargetSizePdfEngine(app);val result=engine.prepare(targetSizePdfRequest(DocumentSession(listOf(page)),targetBytes=512_000))as TargetSizePdfResult.TargetReached
        try{assertNear(pixel,rendered(result.prepared.file))}finally{engine.discard(result.prepared)}
        assertFalse(result.prepared.file.exists());assertArrayEquals(before,source.readBytes())
    }
    @Test fun realSearchablePdfRecognizesAcceptedSourceAndNeverReusesOldOcr()=runBlocking {
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
                pageId=page.id.value,originalUri=Uri.fromFile(source),filter=page.filter,adjustments=settings))))
        assertEquals(1,ocrCalls);assertTrue(result is SearchablePdfPreparedExport.Ready)
        val prepared=result as SearchablePdfPreparedExport.Ready
        try {
            PDDocument.load(prepared.temporaryFile).use{assertEquals(text,PDFTextStripper().getText(it).trim())}
            assertNear(expected()[290*w+353],rendered(prepared.temporaryFile))
        } finally {coordinator.discardPreparedExport(prepared)}
        assertFalse(prepared.temporaryFile.exists());assertArrayEquals(before,source.readBytes())
    }
    @Test fun savedAndRestoredThumbnailUseAcceptedPngAndManualOnce() {
        val store=org.synapseworks.pageharbor.library.LibraryFileStore(app,File(root,"library"))
        val thumbnail=File(root,"thumb.jpg")
        assertTrue(store.createRestoreThumbnail(source,thumbnail,0,page.filter.name,settings.brightness,settings.contrast))
        val decoded=BitmapFactory.decodeFile(thumbnail.path)
        try {
            assertEquals(480,decoded.width);assertEquals(360,decoded.height)
            val full=Bitmap.createBitmap(expected(),w,h,Bitmap.Config.ARGB_8888)
            val expected=Bitmap.createScaledBitmap(full,480,360,true)
            try{for((x,y)in listOf(16 to 8,160 to 8,16 to 16,160 to 16,330 to 271))assertNear(expected.getPixel(x,y),decoded.getPixel(x,y))}finally{expected.recycle();full.recycle()}
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
    fun passwordCaptureStaysFrozenWhenASelectedBatchChangesAcceptedFingerPage()=runBlocking {
        val model=withContext(Dispatchers.Main){PasswordPdfExportViewModel(app)};val owner=PageHarborSessionViewModel();var capturedRoot:File?=null
        try {
            withContext(Dispatchers.Main){owner.installDocumentSessionForTest(DocumentSession(listOf(page,page.copy(id=DocumentPageId(page.id.value+1)))));model.open(normalPdfExportPlan(owner.documentSession))}
            val capture=requireNotNull(model.state.source);capturedRoot=capture.parentFile;val capturedBytes=capture.readBytes();val pixel=expected()[290*w+353]
            withContext(Dispatchers.Main){owner.batchEditor.open();owner.batchEditor.select(setOf(page.id.value));owner.batchEditor.setEdit(BatchPageEdit(filter=DocumentFilter.MOIRE_LOW_V1,brightness=-50));owner.batchEditor.apply()}
            withTimeout(30_000){while(owner.batchEditor.state?.busy==true)delay(10)}
            assertEquals(BatchEditOutcome.APPLIED,owner.batchEditor.state?.outcome)
            val secret="synthetic-m28-key".toCharArray();withContext(Dispatchers.Main){model.prepare(secret)};assertTrue(secret.all{it=='\u0000'})
            withTimeout(30_000){while(model.state.busy)delay(10)}
            assertNear(pixel,rendered(requireNotNull(model.state.output),"synthetic-m28-key"));assertArrayEquals(capturedBytes,capture.readBytes());assertArrayEquals(before,source.readBytes())
        } finally {withContext(Dispatchers.Main){owner.batchEditor.close();model.close()};withTimeout(30_000){while(capturedRoot?.exists()==true)delay(10)}}
    }
    @Test fun allRotationsKeepAnnotationAndSignatureAppearanceAfterSpatialAndManualPass()=runBlocking {
        val layer=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer()
        for(rotation in DocumentPageRotation.entries){
            val marked=page.copy(rotation=rotation,annotations=layer)
            val input=BitmapFactory.decodeFile(source.path)
            val preview=applyPreviewRotationAndFilter(input,rotation,page.filter,settings,layer)
            val result=recomposeNormalPdf(app,(normalPdfExportPlan(DocumentSession(listOf(marked)))as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
            try {
                ParcelFileDescriptor.open(result.file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(0).use{p->
                    val rendered=Bitmap.createBitmap(preview.width,preview.height,Bitmap.Config.ARGB_8888)
                    try{p.render(rendered,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // Independent signature dot and interior highlight, away from the arrow/outline strokes.
                        for((index,point) in listOf(org.synapseworks.pageharbor.document.annotation.AnnotationPoint(.61f,.28f),org.synapseworks.pageharbor.document.annotation.AnnotationPoint(.25f,.45f)).withIndex()){
                            val q=point.rotated(rotation.degrees);val x=(q.x*preview.width).toInt();val y=(q.y*preview.height).toInt()
                            for(bitmap in listOf(preview,rendered))if(index==0)assertTrue(Color.red(bitmap.getPixel(x,y))<80)else assertTrue(Color.blue(bitmap.getPixel(x,y))<210)
                            for(shift in listOf(0,8,16))assertEquals((preview.getPixel(x,y) ushr shift and 255).toFloat(),(rendered.getPixel(x,y) ushr shift and 255).toFloat(),16f)
                        }
                    }finally{rendered.recycle()}
                }}}
            }finally{deleteNormalPdfRecomposition(result.file);if(preview!==input)preview.recycle();input.recycle()}
        }
        assertArrayEquals(before,source.readBytes())
    }

    @Test fun everyExistingFilterAndManualPassComposeOnceAfterLosslessInpainting() {
        val bitmap=BitmapFactory.decodeFile(source.path);val pixels=IntArray(w*h);try{bitmap.getPixels(pixels,0,w,0,0,w,h)}finally{bitmap.recycle()}
        for(filter in DocumentFilter.entries){
            val expected=DocumentImageFilterEngine.apply(ArgbImage(w,h,pixels),filter,settings)
            val stream=ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({source.inputStream()},stream,filter,adjustments=settings))
            val bytes=stream.toByteArray();val actual=BitmapFactory.decodeByteArray(bytes,0,bytes.size);try{for((x,y)in listOf(16 to 8,160 to 8,286 to 245,353 to 290))assertNear(expected.pixels[y*w+x],actual.getPixel(x,y))}finally{actual.recycle()}
        };assertArrayEquals(before,source.readBytes());assertArrayEquals(inputBytes,inputSource.readBytes())
    }
}
