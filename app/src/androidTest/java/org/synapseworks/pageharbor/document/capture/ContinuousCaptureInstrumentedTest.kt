package org.synapseworks.pageharbor.document.capture

import android.app.Application
import android.graphics.*
import android.media.ExifInterface
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter

class ContinuousCaptureInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private lateinit var model:ContinuousCaptureViewModel
    private lateinit var owner:PageHarborSessionViewModel
    private lateinit var viewModels:ViewModelStore
    private lateinit var bytes:ByteArray
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(block:()->Boolean)=runBlocking{withTimeout(30_000){while(!block())delay(5)}}
    @Before fun setup() {
        check(app.packageName.endsWith(".phase2test"))
        val b=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888)
        try {b.eraseColor(Color.WHITE);Canvas(b).drawRect(0f,0f,500f,300f,Paint().apply{color=Color.RED});bytes=java.io.ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,92,it)}.toByteArray()}finally{b.recycle()}
        main {viewModels=ViewModelStore();model=ViewModelProvider(viewModels,ViewModelProvider.AndroidViewModelFactory(app))[ContinuousCaptureViewModel::class.java];owner=PageHarborSessionViewModel();check(model.open(owner.beginContinuousRequest()!!,owner.documentRevision))}
    }
    @After fun cleanup(){main{model.request?.let(owner::cancelContinuousRequest);model.cancel();viewModels.clear();owner.clearScan()}}
    private fun capture(orientation:Int=ExifInterface.ORIENTATION_NORMAL) {
        main{model.capture{file,done->Thread{
            file.writeBytes(bytes)
            if(orientation!=ExifInterface.ORIENTATION_NORMAL)ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
            done(true)
        }.start()}}
        waitFor{model.state?.busy==false}
    }
    @Test fun capturedFilesAppendAtomicallyThenUseNormalPdfAndJpegExports()=runBlocking {
        capture();capture();assertEquals(2,model.state!!.pages.size)
        assertTrue(owner.documentPages.isEmpty());main{model.finish()}
        val input=withContext(Dispatchers.IO){requireNotNull(model.input())}
        val files=model.state!!.pages.map{it.file}
        main{assertTrue(owner.completeContinuousRequest(model.request!!,model.state!!.revision,input));model.transferred()}
        assertEquals(2,owner.documentPages.size);assertTrue(files.all{it.exists()})
        val page=owner.documentPages.first();assertEquals(DocumentResourceOwnership.RME_OWNED_TEMPORARY,page.source.ownership)
        val result=recomposeNormalPdf(app,(normalPdfExportPlan(owner.documentSession)as NormalPdfExportPlan.RecomposeFromPages).pages)as NormalPdfRecompositionResult.Ready
        try{assertTrue(result.file.length()>0)}finally{deleteNormalPdfRecomposition(result.file)}
        main{owner.setPageFilter(page.id.value,DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1)}
        val jpeg=java.io.ByteArrayOutputStream();assertEquals(PageExportResult.Success,writeFilteredJpegToDestination({files.first().inputStream()},jpeg,DocumentFilter.SHADOW_GLARE_STRONG_V1))
        main{owner.clearScan()};assertTrue(files.none{it.exists()})
    }
    @Test fun failurePauseCancelAndLateDuplicateCallbacksNeverDeleteEarlierPagesOrPublishStaleOnes() {
        capture();val first=model.state!!.pages.single().file
        main{model.capture{_,done->done(false)}};waitFor{model.state?.busy==false};assertTrue(first.exists());assertEquals(1,model.state!!.pages.size)
        var target:File?=null;var complete:((Boolean)->Unit)?=null
        main{model.capture{file,done->target=file;complete=done}}
        waitFor{target!=null};target!!.writeBytes(bytes)
        main{model.pause();model.resume()};complete!!(true);complete!!(true)
        waitFor{model.state?.busy==false};assertEquals(1,model.state!!.pages.size);assertTrue(first.exists())
        main{model.capture{file,done->target=file;complete=done}}
        waitFor{target?.name?.startsWith("4.")==true};target!!.writeBytes(bytes)
        main{owner.cancelContinuousRequest(model.request!!);model.cancel()};complete!!(true);complete!!(true)
        waitFor{!first.exists()&&!target!!.exists()};assertTrue(owner.documentPages.isEmpty());waitFor{model.canOpen}
    }
    @Test fun finishWaitsForInFlightAndMalformedOrOversizedDataIsRejected() {
        var target:File?=null;var complete:((Boolean)->Unit)?=null
        main{model.capture{file,done->target=file;complete=done};model.finish()}
        waitFor{target!=null};assertEquals(CapturePhase.CAMERA,model.state!!.phase);target!!.writeBytes(bytes);complete!!(true)
        waitFor{model.state?.phase==CapturePhase.REVIEW};assertEquals(1,model.state!!.pages.size)
        main{model.retake();model.resume();model.capture{file,done->Thread{file.writeBytes(byteArrayOf(1,2,3));done(true)}.start()}}
        waitFor{model.state?.busy==false};assertEquals(1,model.state!!.pages.size)
    }
    @Test fun allExifOrientationsAreNormalizedBeforeSourceOcrPreviewAndExportUse() {
        for(orientation in 1..8) {
            capture(orientation);val page=model.state!!.pages.last()
            assertTrue(exifOrientationIsIdentity(readOrientation{page.file.inputStream()}))
            if(orientation in listOf(5,6,7,8)){assertEquals(1200,page.metadata.width);assertEquals(1600,page.metadata.height)}
            else{assertEquals(1600,page.metadata.width);assertEquals(1200,page.metadata.height)}
            if(orientation==6) {
                val decoded=BitmapFactory.decodeFile(page.file.path)
                try{assertTrue(Color.red(decoded.getPixel(decoded.width-20,20))>200);assertTrue(Color.green(decoded.getPixel(decoded.width-20,20))<50)}finally{decoded.recycle()}
            }
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=28)
    @Test fun deterministicWarmBenchmarkMeasuresPreparationSeparatelyFromInjectedCamera() {
        val rows=mutableListOf("repetition,index,requestToBackendMs,injectedProviderMs,saveToObservedReadyMs,rmeTotalObservedMs,totalMs")
        val javaBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();val nativeBefore=android.os.Debug.getNativeHeapAllocatedSize()
        val preparation=mutableListOf<Double>();val maximum=AtomicInteger();val concurrent=AtomicInteger();var heartbeat=0
        val mainWrites=AtomicInteger();var oldPolicy:android.os.StrictMode.ThreadPolicy?=null
        main{oldPolicy=android.os.StrictMode.getThreadPolicy();android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskWrites().penaltyListener(java.util.concurrent.Executor{it.run()}){violation->
                if(violation.stackTrace.any{it.className.startsWith("org.synapseworks.pageharbor.document.capture")})mainWrites.incrementAndGet()
            }.build())}
        try {repeat(3){rep->
            repeat(33){i->
                val providerBegin=java.util.concurrent.atomic.AtomicLong();val providerDone=java.util.concurrent.atomic.AtomicLong();val begin=SystemClock.elapsedRealtimeNanos()
                main{model.capture{file,done->
                    providerBegin.set(SystemClock.elapsedRealtimeNanos());val active=concurrent.incrementAndGet();maximum.updateAndGet{maxOf(it,active)}
                    Thread{file.writeBytes(bytes)
                        if(i%10==0)ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
                        providerDone.set(SystemClock.elapsedRealtimeNanos());concurrent.decrementAndGet();done(true)}.start()
                };heartbeat++}
                waitFor{model.state?.busy==false};val ready=SystemClock.elapsedRealtimeNanos()
                if(i>=3){val preflight=(providerBegin.get()-begin)/1e6;val process=(ready-providerDone.get())/1e6;val rme=preflight+process;preparation+=rme
                    rows.add("$rep,${i-3},$preflight,${(providerDone.get()-providerBegin.get())/1e6},$process,$rme,${(ready-begin)/1e6}")}
            }
        }
        }finally{main{android.os.StrictMode.setThreadPolicy(requireNotNull(oldPolicy))}}
        assertEquals(0,mainWrites.get())
        val sorted=preparation.sorted();val p95=sorted[(sorted.size*0.95).toInt().coerceAtMost(sorted.lastIndex)]
        File(app.getExternalFilesDir(null),"m10-deterministic.csv").writeText(rows.joinToString("\n")+"\n")
        File(app.getExternalFilesDir(null),"m10-deterministic-summary.txt").writeText("api=36;configuration=1600x1200 JPEG92 synthetic;warmups=3 per repetition;repetitions=3;measured=90;accepted=${model.state!!.pages.size};maxOwnedInFlight=${maximum.get()};queued=0;heartbeats=$heartbeat;mainThreadCaptureWrites=${mainWrites.get()};p50Ms=${sorted[sorted.size/2]};p95Ms=$p95;javaUsedBefore=$javaBefore;javaUsedAfter=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()};nativeBefore=$nativeBefore;nativeAfter=${android.os.Debug.getNativeHeapAllocatedSize()};memoryScope=sampled-not-peak;exif90=every-tenth-source;physicalCamera=NOT_RUN\n")
        assertEquals(99,model.state!!.pages.size);assertEquals(99,heartbeat);assertEquals(1,maximum.get());assertTrue(p95<=500)
    }

    @Test fun oneTenAndFiftyPageBatchesPreserveOrderThroughPdfSave() = runBlocking {
        val report = android.os.Bundle()
        for (count in listOf(1, 10, 50)) {
            if (model.state == null) main { check(model.open(owner.beginContinuousRequest()!!, owner.documentRevision)) }
            val colors = List(count) { index ->
                Color.rgb(32 + index * 29 % 190, 32 + index * 41 % 190, 32 + index * 53 % 190)
            }
            val readyMs = mutableListOf<Long>()
            for (color in colors) {
                val image = Bitmap.createBitmap(192, 128, Bitmap.Config.ARGB_8888)
                try {
                    image.eraseColor(color)
                    bytes = java.io.ByteArrayOutputStream().also {
                        check(image.compress(Bitmap.CompressFormat.JPEG, 96, it))
                    }.toByteArray()
                } finally {
                    image.recycle()
                }
                val start = SystemClock.elapsedRealtime()
                capture()
                readyMs += SystemClock.elapsedRealtime() - start
            }
            main {
                model.finish()
                if (count > 1) check(model.move(0, 1))
            }
            val expectedColors = colors.toMutableList()
            if (count > 1) expectedColors.add(1, expectedColors.removeAt(0))
            val input = withContext(Dispatchers.IO) { requireNotNull(model.input()) }
            main {
                val request = requireNotNull(model.request)
                val revision = requireNotNull(model.state).revision
                assertTrue(model.transfer { owner.completeContinuousRequest(request, revision, input) })
            }
            val started = SystemClock.elapsedRealtime()
            val plan = normalPdfExportPlan(owner.documentSession) as NormalPdfExportPlan.RecomposeFromPages
            val pdf = recomposeNormalPdf(app, plan.pages) as NormalPdfRecompositionResult.Ready
            val saveMs = SystemClock.elapsedRealtime() - started
            try {
                android.os.ParcelFileDescriptor.open(pdf.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).let { descriptor ->
                    android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                        assertEquals(count, renderer.pageCount)
                        for ((index, expected) in expectedColors.withIndex()) {
                            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                            try {
                                renderer.openPage(index).use { it.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                val pixel = bitmap.getPixel(12, 12)
                                assertTrue("Red channel at page $index", kotlin.math.abs(Color.red(pixel) - Color.red(expected)) <= 6)
                                assertTrue("Green channel at page $index", kotlin.math.abs(Color.green(pixel) - Color.green(expected)) <= 6)
                                assertTrue("Blue channel at page $index", kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) <= 6)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            } finally {
                deleteNormalPdfRecomposition(pdf.file)
                main { owner.clearScan() }
            }
            readyMs.sort()
            report.putString("batch$count", "ready median ${readyMs[readyMs.size / 2]} ms, p95 ${readyMs[(readyMs.size * .95).toInt().coerceAtMost(readyMs.lastIndex)]} ms, PDF $saveMs ms")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, report)
    }

}
