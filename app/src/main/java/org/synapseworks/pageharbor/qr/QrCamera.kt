package org.synapseworks.pageharbor.qr

import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import org.synapseworks.pageharbor.document.capture.ContinuousCameraConfiguration

internal object QrCameraConfiguration {
    fun analysis(rotation: Int): ImageAnalysis = ImageAnalysis.Builder()
        .setTargetRotation(rotation)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(960,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
            .setResolutionFilter{sizes,_->sizes.filter{it.width<=QR_MAX_EDGE && it.height<=QR_MAX_EDGE && it.width.toLong()*it.height<=QR_MAX_PIXELS}}
            .build()).build()
}

internal class CameraQrFrame(private val image: ImageProxy): QrFrame {
    override val buffer get()=image.planes[0].buffer
    override val width get()=image.width
    override val height get()=image.height
    override val rowStride get()=image.planes[0].rowStride
    override val pixelStride get()=image.planes[0].pixelStride
    override val left get()=image.cropRect.left
    override val top get()=image.cropRect.top
    override val cropWidth get()=image.cropRect.width()
    override val cropHeight get()=image.cropRect.height()
    override val rotation get()=image.imageInfo.rotationDegrees
    override fun close()=image.close()
}

/** Bind only owned use cases. Disposal never unbinds M10 or another feature's camera. */
@Composable internal fun QrCamera(model: QrViewModel, owner: String, generation: Long, modifier: Modifier) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val view=remember(owner,generation){PreviewView(context).apply{
        implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER
    }}
    AndroidView(factory={view},modifier=modifier.testTag("qr-viewfinder"))
    DisposableEffect(owner,generation,lifecycle,view){
        var disposed=false
        var provider: ProcessCameraProvider?=null
        var preview: Preview?=null
        var analysis: ImageAnalysis?=null
        var camera: Camera?=null
        val executor=Executors.newSingleThreadExecutor()
        val observer=Observer<CameraState>{if(!disposed && it.error!=null)model.cameraProblem(owner,generation)}
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!disposed)try {
                val p=future.get();provider=p
                check(p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                val rotation=view.display?.rotation?:android.view.Surface.ROTATION_0
                val pre=ContinuousCameraConfiguration.preview(rotation)
                val analyzer=QrCameraConfiguration.analysis(rotation)
                preview=pre;analysis=analyzer
                analyzer.setAnalyzer(executor){image->model.frames.analyze(CameraQrFrame(image),owner,generation)}
                pre.setSurfaceProvider(view.surfaceProvider)
                camera=p.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,pre,analyzer)
                camera!!.cameraInfo.cameraState.observe(lifecycle,observer)
            }catch(_:Exception){if(!disposed)model.cameraProblem(owner,generation)}
        },ContextCompat.getMainExecutor(context))
        onDispose {
            disposed=true
            analysis?.clearAnalyzer()
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            val useCases=listOfNotNull(preview,analysis).toTypedArray()
            if(useCases.isNotEmpty())provider?.unbind(*useCases)
            // Queued CameraX analyzer callbacks must run their finally/close, rather than be dropped.
            executor.shutdown()
        }
    }
}
