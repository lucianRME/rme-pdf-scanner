package org.synapseworks.pageharbor.document.scalability

import android.graphics.*
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*

class M01BitmapSafetyInstrumentedTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test fun sampledJpegPngWebpPreserveSourceAndCloseStreams() {
        for(format in listOf(Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.WEBP)) {
            val file=File(context.cacheDir,"m01-format-image")
            val bitmap=Bitmap.createBitmap(2048,1536,Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(Color.BLUE);file.outputStream().use{assertTrue(bitmap.compress(format,95,it))}}
            finally {bitmap.recycle()}
            try {
                val before=digest(file);var opens=0;var closes=0
                val decoded=decodeDisplayBitmap({
                    opens++
                    object:FilterInputStream(file.inputStream()) {
                        var closed=false
                        override fun close(){if(!closed){closed=true;closes++};super.close()}
                    }
                },BitmapDisplaySize(192,192))
                assertNotNull(decoded)
                try {assertTrue(decoded!!.width<=192 && decoded.height<=192);assertTrue(Color.blue(decoded.getPixel(20,20))>=250 && Color.red(decoded.getPixel(20,20))<=5 && Color.green(decoded.getPixel(20,20))<=5)}
                finally {decoded?.recycle()}
                assertEquals(opens,closes);assertArrayEquals(before,digest(file))
            } finally {file.delete()}
        }
    }

    @Suppress("DEPRECATION")
    @Test fun memoryPressureRetriesOnceAndNextRequestStillSucceeds() {
        val file=File(context.cacheDir,"m01-memory-image.jpg")
        fixture(file)
        try {
            var attempts=0
            val recovered=decodeDisplayBitmap({file.inputStream()},BitmapDisplaySize(800,800),decodeRegion={decoder,options->
                attempts++
                if(attempts==1)throw OutOfMemoryError("Synthetic codec pressure")
                decoder.decodeRegion(Rect(0,0,decoder.width,decoder.height),options)
            })
            assertEquals(2,attempts);assertNotNull(recovered);recovered?.recycle()
            attempts=0
            assertNull(decodeDisplayBitmap({file.inputStream()},BitmapDisplaySize(800,800),decodeRegion={_,_->
                attempts++;throw OutOfMemoryError("Synthetic sustained pressure")
            }))
            assertEquals(2,attempts)
            val budgeted=decodeDisplayBitmap({file.inputStream()},BitmapDisplaySize(1600,1600),65536)
            try {assertNotNull(budgeted);assertTrue(budgeted!!.allocationByteCount<=65536)} finally {budgeted?.recycle()}
            val next=decodeDisplayBitmap({file.inputStream()},BitmapDisplaySize(800,800))
            assertNotNull(next);next?.recycle()
        } finally {file.delete()}
    }

    @Test fun twelveMegapixelOcrAndFullResolutionExportKeepTheirOriginalPolicies()=runBlocking {
        val source=File(context.cacheDir,"m01-fidelity-source.jpg")
        val exported=File(context.cacheDir,"m01-fidelity-export.jpg")
        fixture(source)
        try {
            val hash=digest(source)
            val ocr=MlKitOcrEngine().recognize(listOf(OcrPage(imageMetadata=DocumentImageMetadata(source.length(),4032,3024)) {source.inputStream()}))
            assertEquals(1,ocr.pages.size);assertNull(ocr.pages.single().error)
            assertTrue(ocr.pages.single().text.contains("synthetic",true))
            assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({source.inputStream()},exported.outputStream(),DocumentFilter.GRAYSCALE,
                imageMetadata=DocumentImageMetadata(source.length(),4032,3024)))
            assertEquals(4032 to 3024,readImageBounds {exported.inputStream()})
            val page=DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),
                DocumentSourceCategory.SELECTED_IMAGE,imageMetadata=DocumentImageMetadata(source.length(),4032,3024))
            val plan=normalPdfExportPlan(DocumentSession(listOf(page))) as NormalPdfExportPlan.RecomposeFromPages
            val result=recomposeNormalPdf(context,plan.pages)
            assertTrue(result is NormalPdfRecompositionResult.Ready)
            val pdf=(result as NormalPdfRecompositionResult.Ready).file
            try {
                PDDocument.load(pdf).use { document ->
                    assertEquals(1,document.numberOfPages)
                    val resources=document.getPage(0).resources
                    val images=resources.xObjectNames.map { resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                    assertTrue(images.any { it.width==4032 && it.height==3024 })
                }
            } finally {deleteNormalPdfRecomposition(pdf)}
            assertArrayEquals(hash,digest(source))
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{
                putString("m01_fidelity","12MP bundled OCR successful; filtered export 4032x3024; source SHA256 unchanged")
            })
        } finally {source.delete();exported.delete()}
    }

    @Test fun failedPdfPreviewRenderingReleasesItsAllocation() {
        for (oom in listOf(false,true)) {
            var allocated: Bitmap?=null
            val result=renderDisplayBitmap(200,300) { bitmap ->
                allocated=bitmap
                if(oom)throw OutOfMemoryError("Synthetic render pressure") else throw IllegalStateException("Synthetic render failure")
            }
            assertNull(result);assertTrue(allocated!!.isRecycled)
        }
        assertNull(renderDisplayBitmap(50_000,50_000) { fail("Oversized preview allocated") })
        val next=renderDisplayBitmap(200,300) { it.eraseColor(Color.WHITE) }
        assertNotNull(next);assertFalse(next!!.isRecycled);next.recycle()
    }

    private fun fixture(file:File) {
        val bitmap=Bitmap.createBitmap(4032,3024,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("RME synthetic 12345",150f,900f,Paint().apply{color=Color.BLACK;textSize=160f})
            file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}
        } finally {bitmap.recycle()}
    }
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
