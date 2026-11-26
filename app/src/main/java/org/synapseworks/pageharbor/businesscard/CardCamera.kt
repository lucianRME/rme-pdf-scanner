package org.synapseworks.pageharbor.businesscard

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.document.capture.ContinuousCameraConfiguration

@Composable internal fun CardCamera(owner: String, epoch: Long, ready: (ImageCapture?) -> Unit, failed: () -> Unit, tag: String, modifier: Modifier) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    val onReady by rememberUpdatedState(ready); val onFailed by rememberUpdatedState(failed)
    val view = remember(owner, epoch) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView(factory = { view }, modifier = modifier.testTag(tag))
    DisposableEffect(owner, epoch, lifecycle, view) {
        var disposed = false; var provider: ProcessCameraProvider? = null; var preview: Preview? = null; var capture: ImageCapture? = null; var camera: Camera? = null
        val observer = Observer<CameraState> { if(!disposed && it.error != null) { onReady(null); onFailed() } }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ if(!disposed) try {
            val p = future.get(); provider = p; check(p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
            val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
            val pre = ContinuousCameraConfiguration.preview(rotation); val still = ContinuousCameraConfiguration.capture(rotation)
            preview = pre; capture = still; pre.setSurfaceProvider(view.surfaceProvider)
            camera = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, pre, still)
            camera!!.cameraInfo.cameraState.observe(lifecycle, observer); onReady(still)
        } catch(_: Exception) { if(!disposed) { onReady(null); onFailed() } } }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true; onReady(null); camera?.cameraInfo?.cameraState?.removeObserver(observer)
            val owned = listOfNotNull(preview, capture).toTypedArray(); if(owned.isNotEmpty()) provider?.unbind(*owned)
        }
    }
}
