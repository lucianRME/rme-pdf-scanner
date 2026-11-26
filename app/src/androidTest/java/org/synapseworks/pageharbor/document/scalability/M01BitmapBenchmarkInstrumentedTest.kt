package org.synapseworks.pageharbor.document.scalability

import android.graphics.*
import android.net.Uri
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.ui.home.FilteredPreviewRequest
import org.synapseworks.pageharbor.ui.home.decodeDocumentPreview
import org.synapseworks.pageharbor.ui.home.decodeOcrDocumentPreview
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap

/** Synthetic decoder comparison. Run the same class against pre-M01 and post-M01 app builds. */
class M01BitmapBenchmarkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("m01Phase") ?: "after"
    private val photographic get() = InstrumentationRegistry.getArguments().getString("m01Photo") == "true"
    private val legacy get() = phase.endsWith("before")

    @Test fun representativeHighResolutionDecodes() {
        assertTrue(context.packageName.endsWith(".m00benchmark"))
        val phase = InstrumentationRegistry.getArguments().getString("m01Phase") ?: "after"
        val root = File(context.cacheDir, "m01-bitmaps").apply { mkdirs() }
        try {
            for ((width,height) in listOf(4032 to 3024, 6000 to 4000, 8000 to 6000)) {
                val file = File(root, "synthetic.jpg")
                fixture(file,width,height)
                val hash = digest(file)
                val thumb = File(root,"thumbnail.jpg")
                val store = LibraryFileStore(context)
                measure(phase,width,height,"thumbnail_generation") {
                    assertTrue(store.createRestoreThumbnail(file,thumb,0,"ORIGINAL"))
                    null
                }
                // The measured 144px display decoder on the same real 480px library artifact as baseline.
                measure(phase,width,height,"library_thumbnail_144px") { decodeThumbnail(thumb) }
                // Stress the same decoder against a raw photograph; real library thumbnails are bounded.
                measure(phase,width,height,"raw_thumbnail_144px") { decodeThumbnail(file) }
                if (width==4032) {
                    measure(phase,width,height,"review_preview") { decodeReview(file,width,height) }
                    measure(phase,width,height,"ocr_preview") { decodeOcrPreview(file,width,height) }
                }
                assertArrayEquals(hash,digest(file))
                assertTrue(thumb.delete())
                assertTrue(file.delete())
            }
        } finally { root.deleteRecursively() }
    }

    private fun decodeThumbnail(file:File): Bitmap? =
        if (legacy) BitmapFactory.decodeFile(file.path) else decodeDisplayBitmap({file.inputStream()},BitmapDisplaySize(144,144))

    private fun decodeReview(file:File,width:Int,height:Int):Bitmap? = if(legacy) legacyPreview(file) else decodeDocumentPreview(
        context.contentResolver,Uri.fromFile(file),FilteredPreviewRequest(1,"synthetic",DocumentFilter.ORIGINAL,
            imageMetadata=DocumentImageMetadata(file.length(),width,height)),BitmapDisplaySize(1600,1600))

    private fun decodeOcrPreview(file:File,width:Int,height:Int):Bitmap? = if(legacy) legacyPreview(file) else decodeOcrDocumentPreview(
        context.contentResolver,Uri.fromFile(file),DocumentImageMetadata(file.length(),width,height),BitmapDisplaySize(600,800))

    // Frozen pre-M01 two-pass 1600px preview algorithm, for controlled textured-image comparisons.
    private fun legacyPreview(file:File):Bitmap? {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        file.inputStream().use{BitmapFactory.decodeStream(it,null,bounds)}
        var sample=1
        while(maxOf((bounds.outWidth.toLong()+sample-1)/sample,(bounds.outHeight.toLong()+sample-1)/sample)>1600 ||
            ((bounds.outWidth.toLong()+sample-1)/sample)*((bounds.outHeight.toLong()+sample-1)/sample)>2_500_000L) sample*=2
        return file.inputStream().use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}
    }

    private fun measure(phase:String,width:Int,height:Int,operation:String,decode:()->Bitmap?) {
        System.gc(); SystemClock.sleep(150)
        val running=AtomicBoolean(true);val javaPeak=AtomicLong();val nativePeak=AtomicLong();val pssPeak=AtomicLong()
        val sampler=Thread {
            while(running.get()) {
                val rt=Runtime.getRuntime();javaPeak.accumulateAndGet(rt.totalMemory()-rt.freeMemory(),::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),::maxOf);pssPeak.accumulateAndGet(Debug.getPss(),::maxOf)
                try { Thread.sleep(20) } catch (_:InterruptedException) { break }
            }
        }
        sampler.start()
        var bitmap:Bitmap?=null
        val started=SystemClock.elapsedRealtimeNanos()
        try {
            bitmap=decode()
            assertTrue(operation=="thumbnail_generation" || bitmap!=null)
            val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
            SystemClock.sleep(100) // Include the live bitmap in sampled memory, not fixture creation.
            val result=JSONObject().put("phase",phase).put("source_width",width).put("source_height",height)
                .put("operation",operation).put("photographic_texture",photographic).put("elapsed_ms",elapsed).put("decoded_width",bitmap?.width?:0)
                .put("decoded_height",bitmap?.height?:0).put("allocation_bytes",bitmap?.allocationByteCount?:0)
                .put("sampled_java_bytes",javaPeak.get()).put("sampled_native_bytes",nativePeak.get())
                .put("sampled_pss_kib",pssPeak.get()).put("sample_ms",20)
            instrumentation.sendStatus(0,Bundle().apply {putString("m01_result",result.toString())})
        } finally { bitmap?.recycle();running.set(false);sampler.interrupt();sampler.join(1000) }
    }

    private fun fixture(file:File,width:Int,height:Int) {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            if(photographic) {
                val row=IntArray(width)
                var noise=0x13579bdf
                for(y in 0 until height) {
                    for(x in 0 until width) {
                        noise=noise*1664525+1013904223
                        val grain=(noise ushr 27)-16
                        row[x]=Color.rgb((50+x*160/width+grain).coerceIn(0,255),
                            (40+y*170/height+grain).coerceIn(0,255),(120+(x+y)%80+grain).coerceIn(0,255))
                    }
                    bitmap.setPixels(row,0,width,0,y,width,1)
                }
            }
            val canvas=Canvas(bitmap)
            val paint=Paint().apply{color=Color.BLACK;textSize=width/22f}
            canvas.drawText("RME synthetic 12345",width/20f,height/3f,paint)
            paint.color=Color.RED;canvas.drawRect(width*0.1f,height*0.6f,width*0.2f,height*0.7f,paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }
        } finally {bitmap.recycle()}
    }
    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
