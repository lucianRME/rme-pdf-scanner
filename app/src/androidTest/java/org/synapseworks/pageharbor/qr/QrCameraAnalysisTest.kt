package org.synapseworks.pageharbor.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.atomic.AtomicReference
import org.synapseworks.pageharbor.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Actual emulator CameraX/YUV ownership. It does not prove physical QR recognition. */
class QrCameraAnalysisTest {
    @Test fun emulatedYuvFramesStayBoundedAndAllDeliveredFramesCloseAcrossLifecycleStop()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName.endsWith(".phase2test"))
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.CAMERA))
        val provider=ProcessCameraProvider.getInstance(context).get(30,TimeUnit.SECONDS)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val activity=AtomicReference<MainActivity>();scenario.onActivity{activity.set(it);it.qr.close()}
        val analysis=QrCameraConfiguration.analysis(android.view.Surface.ROTATION_0)
        val executor=Executors.newSingleThreadExecutor();val delivered=AtomicInteger();val closed=AtomicInteger();val failures=AtomicInteger();val enough=CountDownLatch(1)
        val controller=QrController();controller.open();val token=controller.state!!
        val processor=QrFrameProcessor(Mutex(),controller,controller::completeFrame)
        analysis.setAnalyzer(executor){image->
            delivered.incrementAndGet()
            try{
                check(image.width<=QR_MAX_EDGE && image.height<=QR_MAX_EDGE && image.width.toLong()*image.height<=QR_MAX_PIXELS)
                check(image.imageInfo.rotationDegrees in setOf(0,90,180,270))
                val frame=object:QrFrame by CameraQrFrame(image){override fun close(){image.close();if(closed.incrementAndGet()>=3)enough.countDown()}}
                processor.analyze(frame,token.owner,token.generation)
            }catch(_:Throwable){failures.incrementAndGet();image.close();closed.incrementAndGet();enough.countDown()}
        }
        try{
            withContext(Dispatchers.Main){provider.bindToLifecycle(activity.get(),CameraSelector.DEFAULT_BACK_CAMERA,analysis)}
            assertTrue(enough.await(30,TimeUnit.SECONDS))
        }finally{
            controller.close();scenario.moveToState(Lifecycle.State.CREATED)
            withContext(Dispatchers.Main){analysis.clearAnalyzer();provider.unbind(analysis)}
            executor.shutdown();assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));scenario.close()
        }
        assertEquals(0,failures.get());assertTrue(delivered.get()>=3);assertEquals(delivered.get(),closed.get())
    }
}
