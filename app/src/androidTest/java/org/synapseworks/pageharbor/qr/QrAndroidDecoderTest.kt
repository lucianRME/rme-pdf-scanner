package org.synapseworks.pageharbor.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class QrAndroidDecoderTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val assets get()=instrumentation.context.assets
    @Test fun firstUseBundledDecoderMatchesIndependentOfflineFixtures(){
        check(instrumentation.targetContext.packageName.endsWith(".phase2test"))
        assertEquals(PackageManager.PERMISSION_DENIED,instrumentation.targetContext.packageManager.checkPermission(Manifest.permission.INTERNET,instrumentation.targetContext.packageName))
        for(name in listOf("web","web-90","web-180","web-270","web-inverted","unicode","lines","unsupported","userinfo","wifi","controls","dense","dense-90","dense-180","dense-270")){
            val expected=assets.open("m19/$name.txt").bufferedReader(Charsets.UTF_8).use{it.readText()}
            val closed=AtomicBoolean()
            val result=QrImageReader.read({object:java.io.FilterInputStream(assets.open("m19/$name.png")){override fun close(){closed.set(true);super.close()}}})
            assertTrue(name,result is QrDecode.Found);assertEquals(expected,(result as QrDecode.Found).payload.text);assertTrue(closed.get())
        }
    }
    @Test fun syntheticStridedCameraPipelinePublishesOneResultAndRejectsOldBindings(){
        val bitmap=assets.open("m19/web.png").use{android.graphics.BitmapFactory.decodeStream(it)}!!
        val imageWidth=bitmap.width+2;val imageHeight=bitmap.height+2;val stride=imageWidth*2+5
        val planeBuffer=java.nio.ByteBuffer.allocate(5+stride*imageHeight).apply{position(5)}
        try{for(y in 0 until bitmap.height)for(x in 0 until bitmap.width)planeBuffer.put(5+(y+1)*stride+(x+1)*2,(bitmap.getPixel(x,y) and 255).toByte())}finally{bitmap.recycle()}
        val c=QrController();c.open();val origin=c.state!!;var closes=0
        val processor=QrFrameProcessor(kotlinx.coroutines.sync.Mutex(),c,c::completeFrame)
        fun frame()=object:QrFrame{
            override val buffer get()=planeBuffer;override val width=imageWidth;override val height=imageHeight
            override val rowStride=stride;override val pixelStride=2;override val left=1;override val top=1
            override val cropWidth=imageWidth-2;override val cropHeight=imageHeight-2;override val rotation=90
            override fun close(){closes++}
        }
        processor.analyze(frame(),origin.owner,origin.generation)
        val expected=assets.open("m19/web.txt").bufferedReader(Charsets.UTF_8).use{it.readText()}
        assertEquals(expected,c.state!!.result!!.text);assertEquals(1,closes)
        processor.analyze(frame(),origin.owner,origin.generation);assertEquals(2,closes)
        c.rescan();processor.analyze(frame(),origin.owner,origin.generation);assertEquals(3,closes);assertNull(c.state!!.result)
        val next=c.state!!;processor.analyze(frame(),next.owner,next.generation);assertEquals(4,closes);assertEquals(expected,c.state!!.result!!.text)
    }
    @Test fun rejectedRapidImageRequestCannotCancelTheAcceptedImport()=kotlinx.coroutines.runBlocking {
        val app=instrumentation.targetContext.applicationContext as android.app.Application
        check(app.packageName.endsWith(".phase2test"))
        val store=androidx.lifecycle.ViewModelStore()
        val model=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){
            androidx.lifecycle.ViewModelProvider(store,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[QrViewModel::class.java].also{it.open()}
        }
        val owner=model.state!!
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val file=java.io.File(app.cacheDir,"m19-blocked-import-${java.util.UUID.randomUUID()}.png")
        assets.open("m19/web.png").use{input->file.outputStream().use{input.copyTo(it)}}
        val frame=object:QrFrame{
            override val buffer get():java.nio.ByteBuffer{entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return java.nio.ByteBuffer.wrap(ByteArray(64){(-1).toByte()})}
            override val width=8;override val height=8;override val rowStride=8;override val pixelStride=1
            override val left=0;override val top=0;override val cropWidth=8;override val cropHeight=8;override val rotation=0
            override fun close(){}
        }
        try{
            val worker=executor.submit{model.frames.analyze(frame,owner.owner,owner.generation)}
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){
                assertTrue(model.beginPicker());model.receivePickedImage(android.net.Uri.fromFile(file))
                assertEquals(QrPhase.IMPORTING,model.state!!.phase)
                repeat(4){assertFalse(model.beginPicker())}
            }
            release.countDown();worker.get(10,java.util.concurrent.TimeUnit.SECONDS)
            kotlinx.coroutines.withTimeout(10_000){while(model.state?.phase!=QrPhase.RESULT)kotlinx.coroutines.delay(10)}
            val expected=assets.open("m19/web.txt").bufferedReader(Charsets.UTF_8).use{it.readText()}
            assertEquals(expected,model.state!!.result!!.text)
        }finally{
            release.countDown();executor.shutdown();executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){store.clear()};file.delete()
        }
    }
    @Test fun noCodeMalformedAndOversizedImagesFailWithoutResultAndCloseTheirStreams(){
        for(name in listOf("blank.png","corrupt.png","oversized.png","malformed.bin","structured-0.png","structured-1.png")){
            val closed=AtomicBoolean()
            val r=QrImageReader.read({object:java.io.FilterInputStream(assets.open("m19/$name")){override fun close(){closed.set(true);super.close()}}})
            assertFalse(r is QrDecode.Found);assertTrue(closed.get())
        }
    }
    @Test fun byteLimitAndCancellationAlwaysCloseInput(){
        var closed=false;var supplied=0
        val oversized=object:InputStream(){override fun read()=error("Unexpected single byte read")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int{val n=minOf(length,QrImageReader.MAX_BYTES+1-supplied);if(n==0)return -1;bytes.fill(0,offset,offset+n);supplied+=n;return n}
            override fun close(){closed=true}}
        assertEquals(QrDecode.Invalid,QrImageReader.read({oversized}));assertTrue(closed);assertEquals(QrImageReader.MAX_BYTES+1,supplied)
        closed=false;var checks=0
        val bytes=assets.open("m19/web.png").use{it.readBytes()}
        try{QrImageReader.read({object:ByteArrayInputStream(bytes){override fun close(){closed=true;super.close()}}}){if(++checks>=3)throw CancellationException("Synthetic cancellation")};fail()}catch(_:CancellationException){}
        assertTrue(closed)
    }
}
