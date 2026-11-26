package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.synapseworks.pageharbor.businesscard.CameraCardImage
import org.synapseworks.pageharbor.businesscard.CardCamera
import org.synapseworks.pageharbor.businesscard.CardImage
import org.synapseworks.pageharbor.document.capture.CameraAccess

@Composable
internal fun <T : Any> SpecialistCamera(
    tool: String, title: String, shutterLabel: String, manualLabel: String,
    owner: String, epoch: Long, capturing: Boolean,
    begin: () -> T?, captured: (T, CardImage) -> Unit, failed: (T) -> Unit, unavailable: () -> Unit,
    chooseImage: () -> Unit, manual: () -> Unit, close: () -> Unit,
) {
    val context = LocalContext.current
    var camera by remember(owner, epoch) { mutableStateOf<ImageCapture?>(null) }

    DocumentCameraLayout(
        cameraActive = camera != null,
        capturing = capturing,
        guide = if (tool == "card") ScanGuide.CARD else ScanGuide.DOCUMENT,
        viewfinder = { modifier ->
            CardCamera(owner, epoch, { camera = it }, unavailable, tag = "$tool-viewfinder", modifier = modifier)
        },
        controls = { modifier ->
            LazyColumn(modifier.testTag("$tool-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                item { RmeTextButton(chooseImage, enabled = !capturing, modifier = Modifier.testTag("$tool-image")) { Text("Choose image") } }
                item { RmeTextButton(manual, enabled = !capturing, modifier = Modifier.testTag("$tool-manual")) { Text(manualLabel) } }
                item { RmeTextButton(close, modifier = Modifier.testTag("$tool-close")) { Text("Back to library") } }
            }
        },
        shutter = {
            CaptureShutter(shutterLabel, "$tool-shutter", camera != null && !capturing) {
                val ready = camera ?: return@CaptureShutter
                if (!CameraAccess.allowed(context)) return@CaptureShutter
                val token = begin() ?: return@CaptureShutter
                try {
                    ready.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) = captured(token, CameraCardImage(image))
                        override fun onError(exception: ImageCaptureException) = failed(token)
                    })
                } catch (_: Exception) {
                    failed(token)
                }
            }
        },
    )
}
