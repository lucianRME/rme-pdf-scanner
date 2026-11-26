package org.synapseworks.pageharbor.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportViewModel
import org.synapseworks.pageharbor.document.targetsize.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ui.home.applyPreviewRotationAndFilter

class ManualAdjustmentExportTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var root:File;private lateinit var source:File;private lateinit var before:ByteArray
    private val settings=ManualImageAdjustments(20,25)
    private lateinit var page:DocumentPage
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        root=File(app.cacheDir,"shared-pdfs/m06-export-${UUID.randomUUID()}").apply{check(mkdirs())}
        source=File(root,"source.jpg")
        val bitmap=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888)
        try {bitmap.eraseColor(Color.rgb(64,96,128));source.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}} finally{bitmap.recycle()}
        before=source.readBytes()
        page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,
            imageMetadata=DocumentImageMetadata(source.length(),120,160),filter=DocumentFilter.GRAYSCALE,adjustments=settings)
    }
    @After fun cleanup(){root.deleteRecursively()}
    private fun previewPixel():Int {
        val original=BitmapFactory.decodeFile(source.path)
        val output=applyPreviewRotationAndFilter(original,page.rotation,page.filter,settings)
        return try{output.getPixel(output.width/2,output.height/2)}finally{if(output!==original)output.recycle();original.recycle()}
    }
    private fun assertNear(expected:Int,actual:Int) {for(shift in listOf(0,8,16)) assertTrue(kotlin.math.abs((expected ushr shift and 255)-(actual ushr shift and 255))<=3)}
    private fun render(file:File,index:Int=0):Int=PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer ->renderer.openPage(index).use{page ->
        val bitmap=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888)
        try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap.getPixel(60,80)}finally{bitmap.recycle()}
    }}
    @Test fun previewAndFullSizedJpegApplyFilterAndManualExactlyOnce() {
        val expected=previewPixel()
        assertTrue(Color.red(expected) in 132..136)
        val output=ByteArrayOutputStream()
        assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({source.inputStream()},output,page.filter,adjustments=settings))
        val bytes=output.toByteArray();val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try{assertNear(expected,bitmap.getPixel(60,80))}finally{bitmap.recycle()}
        val sized=ByteArrayOutputStream()
        assertEquals(PageExportResult.Success,writeSizedJpegToDestination({source.inputStream()},sized,page.filter,page.rotation,page.imageMetadata,SizedJpegEncoding(120,160,100),adjustments=settings))
        val small=sized.toByteArray();val decoded=BitmapFactory.decodeByteArray(small,0,small.size)
        try{assertNear(expected,decoded.getPixel(60,80))}finally{decoded.recycle()}
        assertArrayEquals(before,source.readBytes())
    }
    @Test fun selectedBatchPreviewAndPdfUseExactlyOneRecipeAndLeaveOtherPageUntouched()=runBlocking {
        val first=page.copy(filter=DocumentFilter.ORIGINAL,adjustments=ManualImageAdjustments())
        val second=first.copy(id=DocumentPageId(2))
        val recipe=org.synapseworks.pageharbor.document.batch.BatchPageEdit(filter=DocumentFilter.GRAYSCALE,brightness=20,contrast=25,clockwiseQuarterTurns=1)
        val changed=org.synapseworks.pageharbor.document.batch.prepareBatchEdit(DocumentSession(listOf(first,second)),setOf(1),recipe).session
        assertEquals(recipe.preview(first),changed.pages[0]);assertSame(second,changed.pages[1])
        val decoded=BitmapFactory.decodeFile(source.path)
        val visual=applyPreviewRotationAndFilter(decoded,changed.pages[0].rotation,changed.pages[0].filter,changed.pages[0].adjustments)
        val expected=try{visual.getPixel(visual.width/2,visual.height/2)}finally{if(visual!==decoded)visual.recycle();decoded.recycle()}
        assertTrue(Color.red(expected) in 132..136)
        val result=recomposeNormalPdf(app,(normalPdfExportPlan(changed) as NormalPdfExportPlan.RecomposeFromPages).pages) as NormalPdfRecompositionResult.Ready
        try{assertNear(expected,render(result.file,0));assertNear(0xff406080.toInt(),render(result.file,1));assertArrayEquals(before,source.readBytes())}finally{deleteNormalPdfRecomposition(result.file)}
    }
    @Test fun normalPdfContainsAdjustedVisualAndKeepsOriginalSource()=runBlocking {
        val result=recomposeNormalPdf(app,(normalPdfExportPlan(DocumentSession(listOf(page))) as NormalPdfExportPlan.RecomposeFromPages).pages)
        assertTrue(result is NormalPdfRecompositionResult.Ready)
        val file=(result as NormalPdfRecompositionResult.Ready).file
        try{assertNear(previewPixel(),render(file));assertArrayEquals(before,source.readBytes())}finally{deleteNormalPdfRecomposition(file)}
    }
    @Test fun storageFailureAndCancellationCloseDestinationsAndPreserveInput() {
        var closed=false
        val denied=object:OutputStream(){override fun write(b:Int){throw IOException()};override fun write(b:ByteArray,off:Int,len:Int){throw IOException()};override fun close(){closed=true}}
        assertEquals(PageExportResult.WriteFailed,writeFilteredJpegToDestination({source.inputStream()},denied,page.filter,adjustments=settings))
        assertTrue(closed)
        closed=false
        val canceled=object:ByteArrayOutputStream(){override fun close(){closed=true;super.close()}}
        try{writeFilteredJpegToDestination({source.inputStream()},canceled,page.filter,adjustments=settings,isCancellationRequested={true});fail()}catch(_:CancellationException){}
        assertTrue(closed);assertArrayEquals(before,source.readBytes())
    }
    @Test @SdkSuppress(minSdkVersion=35)
    fun passwordWorkflowKeepsItsCapturedAdjustedVisualAfterSessionChanges()=capturedVisualAfterChange(false)

    @Test @SdkSuppress(minSdkVersion=35)
    fun passwordCaptureRemainsFrozenAcrossSelectedBatch()=capturedVisualAfterChange(true)

    private fun capturedVisualAfterChange(batch:Boolean)=runBlocking {
        val model=withContext(Dispatchers.Main){PasswordPdfExportViewModel(app)}
        var owned:File?=null
        try{
            withContext(Dispatchers.Main){model.open(normalPdfExportPlan(DocumentSession(listOf(page))))}
            val captured=requireNotNull(model.state.source);owned=captured.parentFile
            val frozen=captured.readBytes()
            val next=if(batch) org.synapseworks.pageharbor.document.batch.prepareBatchEdit(DocumentSession(listOf(page)),setOf(page.id.value),
                org.synapseworks.pageharbor.document.batch.BatchPageEdit(brightness=-50)).session
            else requireNotNull(DocumentSession(listOf(page)).setAdjustments(page.id,ManualImageAdjustments(-50)))
            assertNotEquals(page.adjustments,next.pages.single().adjustments)
            val secret="synthetic-m06-password".toCharArray()
            withContext(Dispatchers.Main){model.prepare(secret)}
            assertTrue(secret.all{it=='\u0000'})
            withTimeout(30_000){while(model.state.busy)delay(10)}
            val output=requireNotNull(model.state.output)
            PdfRenderer(ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY),LoadParams.Builder().setPassword("synthetic-m06-password").build()).use{renderer ->renderer.openPage(0).use{pdfPage ->
                val bitmap=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888)
                try{pdfPage.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);assertNear(previewPixel(),bitmap.getPixel(60,80))}finally{bitmap.recycle()}
            }}
            assertArrayEquals(frozen,captured.readBytes());assertArrayEquals(before,source.readBytes())
        }finally{withContext(Dispatchers.Main){model.close()};withTimeout(30_000){while(owned?.exists()==true)delay(10)}}
    }
    @Test fun sizeTargetedPdfUsesAdjustedImageAndNeverAnUnadjustedOriginalCopy()=runBlocking {
        val request=targetSizePdfRequest(DocumentSession(listOf(page)),targetBytes=512_000)
        val engine=LocalTargetSizePdfEngine(app)
        val result=engine.prepare(request)
        assertTrue(result is TargetSizePdfResult.TargetReached)
        val candidate=(result as TargetSizePdfResult.TargetReached).prepared
        try{assertNear(previewPixel(),render(candidate.file));assertArrayEquals(before,source.readBytes())}finally{engine.discard(candidate)}
    }
}
