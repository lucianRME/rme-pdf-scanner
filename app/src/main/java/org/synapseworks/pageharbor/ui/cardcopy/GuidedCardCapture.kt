package org.synapseworks.pageharbor.ui.cardcopy

import android.Manifest
import android.app.AppOpsManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.businesscard.CardCamera
import org.synapseworks.pageharbor.document.capture.CameraAccess
import org.synapseworks.pageharbor.document.cardcopy.*
import org.synapseworks.pageharbor.ui.home.CaptureShutter
import org.synapseworks.pageharbor.ui.home.DocumentCameraLayout
import org.synapseworks.pageharbor.ui.home.ScanGuide
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

@Composable
internal fun GuidedCardCapture(
    state: CardCopyWorkflowState.AcquireSides,
    capture: (CardCopySide, CardCopyShutter) -> Unit,
    chooseImage: (CardCopySide) -> Unit,
    review: () -> Unit,
    cancelCapture: () -> Unit,
    close: () -> Unit,
) {
    val complete = state.front is CardCopySideState.Ready && state.back is CardCopySideState.Ready && state.retaking == null
    val advanceWhenComplete = rememberSaveable { !complete }
    if (complete) {
        LaunchedEffect(Unit) { if (advanceWhenComplete) review() }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.Center) {
            RmePrimaryButton(review) { Text(stringResource(R.string.card_copy_review_title)) }
            RmeTextButton(close) { Text(stringResource(R.string.card_copy_close)) }
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val cancelCurrent by rememberUpdatedState(cancelCapture)
    var allowed by remember { mutableStateOf(CameraAccess.allowed(context)) }
    var failed by remember { mutableStateOf(false) }
    var epoch by remember { mutableLongStateOf(0L) }
    var camera by remember { mutableStateOf<ImageCapture?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = CameraAccess.allowed(context)
    }
    DisposableEffect(context, lifecycle) {
        var observing = true
        fun checkAccess() {
            if (!observing) return
            allowed = CameraAccess.allowed(context)
            if (!allowed) { camera = null; cancelCurrent() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkAccess()
            if (event == Lifecycle.Event.ON_STOP) cancelCurrent()
        }
        val ops = context.getSystemService(AppOpsManager::class.java)
        val listener = AppOpsManager.OnOpChangedListener { _, pkg ->
            if (pkg == null || pkg == context.packageName) ContextCompat.getMainExecutor(context).execute { checkAccess() }
        }
        lifecycle.lifecycle.addObserver(observer)
        try { ops.startWatchingMode(AppOpsManager.OPSTR_CAMERA, context.packageName, listener) }
        catch (_: SecurityException) { checkAccess() }
        onDispose {
            observing = false
            lifecycle.lifecycle.removeObserver(observer)
            runCatching { ops.stopWatchingMode(listener) }
            cancelCurrent()
        }
    }
    val side = state.retaking ?: if (state.front.readySource() == null) CardCopySide.FRONT else CardCopySide.BACK
    val front = side == CardCopySide.FRONT
    val slot = if (front) state.front else state.back
    val busy = slot is CardCopySideState.Acquiring || slot is CardCopySideState.Decoding
    val label = stringResource(when {
        !allowed -> R.string.continuous_allow
        failed -> R.string.id_retry_camera
        front -> R.string.id_capture_front
        else -> R.string.id_capture_back
    })
    DocumentCameraLayout(
        modifier = Modifier.testTag("id-camera"),
        guide = ScanGuide.CARD,
        cameraActive = allowed && camera != null && !failed,
        capturing = busy,
        viewfinder = { modifier ->
            if (allowed && !failed) {
                CardCamera("id-copy", epoch, { camera = it }, { camera = null; failed = true }, "id-viewfinder", modifier)
            } else Box(modifier, contentAlignment = Alignment.Center) {
                Text(stringResource(if (failed) R.string.id_no_camera else R.string.id_camera_access), Modifier.padding(24.dp))
            }
        },
        controls = { modifier ->
            LazyColumn(modifier.testTag("id-controls")) {
                item { Text(stringResource(if (front) R.string.id_step_front else R.string.id_step_back),
                    Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium) }
                item { Text(stringResource(if (front) R.string.id_front_hint else R.string.id_back_hint), style = MaterialTheme.typography.bodyMedium) }
                if (slot is CardCopySideState.Failed) item { Text(stringResource(R.string.id_failed_capture), color = MaterialTheme.colorScheme.error) }
                item { RmeTextButton({ chooseImage(side) }, enabled = !busy, modifier = Modifier.testTag("id-image")) { Text(stringResource(R.string.id_choose_image)) } }
                item { RmeTextButton(close, Modifier.testTag("id-close")) { Text(stringResource(R.string.card_copy_close)) } }
            }
        },
        shutter = {
            CaptureShutter(label, "id-shutter", !busy && (!allowed || failed || camera != null)) {
                when {
                    !allowed -> launcher.launch(Manifest.permission.CAMERA)
                    failed -> { failed = false; epoch++ }
                    CameraAccess.allowed(context) -> camera?.let { ready ->
                        capture(side) { file, done ->
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            ready.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(true)
                                override fun onError(exception: ImageCaptureException) = done(false)
                            })
                        }
                    }
                }
            }
        },
    )
}

