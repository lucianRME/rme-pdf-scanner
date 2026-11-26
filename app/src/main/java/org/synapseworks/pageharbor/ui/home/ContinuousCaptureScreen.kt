package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.capture.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter

@Composable
internal fun ContinuousCaptureScreen(model:ContinuousCaptureViewModel,onCancel:()->Unit,onUse:()->Unit,
    onCardCopy: (String) -> Unit, backendOverride:((File,(Boolean)->Unit)->Unit)?=null) {
    val s=model.state?:return
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    fun allowed():Boolean = CameraAccess.allowed(context)
    var permission by remember(s.owner){mutableStateOf(allowed())}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=allowed()}
    var capture by remember(s.owner, s.singlePage){mutableStateOf<ImageCapture?>(null)}
    var index by rememberSaveable(s.owner){mutableIntStateOf(0)}
    var showGrid by rememberSaveable(s.owner) { mutableStateOf(false) }
    DisposableEffect(lifecycle,s.owner) {
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME)permission=allowed()
            if(event==Lifecycle.Event.ON_STOP)model.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        var active=true
        val ops=context.getSystemService(android.app.AppOpsManager::class.java)
        val listener=android.app.AppOpsManager.OnOpChangedListener{op,pkg->
            if((op==null||op==android.app.AppOpsManager.OPSTR_CAMERA)&&(pkg==null||pkg==context.packageName))ContextCompat.getMainExecutor(context).execute {
                if(active&&model.state?.owner==s.owner){permission=allowed();if(!permission)model.pause(CaptureProblem.PERMISSION)}
            }
        }
        try{ops?.startWatchingMode(android.app.AppOpsManager.OPSTR_CAMERA,context.packageName,listener)}catch(_:SecurityException){permission=false;model.pause(CaptureProblem.PERMISSION)}
        onDispose{active=false;lifecycle.lifecycle.removeObserver(observer);runCatching{ops?.stopWatchingMode(listener)}}
    }
    BackHandler {
        if (s.replacing == null) onCancel()
        else if (!s.busy) model.finish()
    }
    Surface(Modifier.fillMaxSize()) {
        if (s.phase == CapturePhase.REVIEW) {
            Column(
                Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(when (s.mode) { CaptureMode.DOCUMENT -> R.string.manual_camera_title; CaptureMode.BATCH -> R.string.continuous_title; CaptureMode.BOOK -> R.string.camera_book_review }), style = MaterialTheme.typography.titleLarge)
                    Text(pluralStringResource(R.plurals.continuous_count, s.pages.size, s.pages.size),
                        Modifier.testTag("capture-count").semantics { liveRegion = LiveRegionMode.Polite })
                    val selected = index.coerceIn(0, s.pages.lastIndex.coerceAtLeast(0))
                    s.pages.getOrNull(selected)?.let { page ->
                        FilteredDocumentPreview(
                            FilteredPreviewRequest(selected.toLong(), page.file.toURI().toString(), DocumentFilter.ORIGINAL,
                                DocumentPageRotation.DEGREES_0, page.metadata),
                            android.net.Uri.fromFile(page.file), selected + 1, s.pages.size,
                            minHeight = 100.dp, maxHeight = 240.dp,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RmeIconButton(
                                onClick = { index = selected - 1 },
                                enabled = selected > 0,
                                modifier = Modifier.testTag("capture-previous"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.continuous_previous))
                            }
                            Text(
                                stringResource(R.string.ocr_page_indicator, selected + 1, s.pages.size),
                                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                textAlign = TextAlign.Center,
                            )
                            RmeIconButton(
                                onClick = { index = selected + 1 },
                                enabled = selected < s.pages.lastIndex,
                                modifier = Modifier.testTag("capture-next"),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.continuous_next))
                            }
                        }
                        if (s.pages.size > 1) {
                            RmeTextButton(
                                onClick = { showGrid = true },
                                modifier = Modifier.fillMaxWidth().testTag("capture-open-grid"),
                            ) { Text(stringResource(R.string.capture_page_grid)) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RmeSecondaryButton(
                                    onClick = { if (model.move(selected, -1)) index = selected - 1 },
                                    enabled = selected > 0,
                                    modifier = Modifier.weight(1f).testTag("capture-move-earlier"),
                                ) { Text(stringResource(R.string.continuous_move_earlier), textAlign = TextAlign.Center) }
                                RmeSecondaryButton(
                                    onClick = { if (model.move(selected, 1)) index = selected + 1 },
                                    enabled = selected < s.pages.lastIndex,
                                    modifier = Modifier.weight(1f).testTag("capture-move-later"),
                                ) { Text(stringResource(R.string.continuous_move_later), textAlign = TextAlign.Center) }
                            }
                        }
                        RmeTextButton(
                            onClick = { model.replace(selected); model.resume() },
                            modifier = Modifier.fillMaxWidth().testTag("capture-replace"),
                        ) { Text(stringResource(R.string.continuous_retake)) }
                    }
                }
                RmePrimaryButton(onClick = onUse, enabled = s.pages.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("capture-use")) {
                    Text(stringResource(when (s.mode) { CaptureMode.DOCUMENT -> R.string.manual_camera_crop; CaptureMode.BATCH -> R.string.continuous_use; CaptureMode.BOOK -> R.string.camera_review_split }))
                }
                if (!s.singlePage) RmeSecondaryButton(
                    onClick = { model.retake(); model.resume() },
                    modifier = Modifier.fillMaxWidth().testTag("capture-more"),
                ) { Text(stringResource(R.string.continuous_more)) }
                RmeTextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("capture-cancel")) {
                    Text(stringResource(R.string.manual_cancel))
                }
            }
        } else {
            val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier, contentAlignment = Alignment.Center) {
                    if (!permission && backendOverride == null) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.continuous_permission))
                            RmePrimaryButton(onClick = { launcher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.testTag("capture-permission")) { Text(stringResource(R.string.continuous_allow)) }
                        }
                    } else if (s.paused) {
                        RmePrimaryButton(onClick = model::resume, modifier = Modifier.testTag("capture-resume")) {
                            Text(stringResource(R.string.continuous_resume))
                        }
                    } else if (backendOverride == null) {
                        key(s.singlePage) {
                            ContinuousCameraPreview(s.owner, s.singlePage, onReady = { capture = it }, onUnavailable = { model.pause(CaptureProblem.CAMERA) })
                        }
                        if (capture == null) Text(stringResource(R.string.continuous_initializing))
                    }
                }
            }
            DocumentCameraLayout(
                viewfinder = viewfinder,
                cameraActive = (capture != null || backendOverride != null) && !s.paused && (permission || backendOverride != null),
                capturing = s.busy,
                modeSelector = { CaptureModeSelector(s.mode, s.canSelectMode, model::selectMode, onCardCopy = { onCardCopy(s.owner) }) },
                controls = { modifier ->
                    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (s.problem != null) Text(stringResource(R.string.continuous_error),
                            Modifier.testTag("capture-error"), color = MaterialTheme.colorScheme.error)
                        if (s.busy) Text(stringResource(R.string.continuous_saving),
                            Modifier.testTag("capture-busy").semantics { liveRegion = LiveRegionMode.Polite })
                        Text(
                            when {
                                s.replacing != null -> stringResource(R.string.continuous_retake_page, index + 1)
                                s.mode == CaptureMode.BOOK -> stringResource(R.string.camera_book_frame)
                                s.singlePage -> stringResource(R.string.manual_camera_page)
                                else -> pluralStringResource(R.plurals.continuous_count, s.pages.size, s.pages.size)
                            },
                            Modifier.testTag("capture-count").semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (!s.singlePage) RmeSecondaryButton(
                            onClick = model::finish, enabled = (s.pages.isNotEmpty() || s.busy) && !s.finishing,
                            modifier = Modifier.testTag("capture-finish"),
                        ) { Text(stringResource(if (s.replacing == null) R.string.continuous_finish else R.string.continuous_back_to_review)) }
                        RmeTextButton(onClick = onCancel, modifier = Modifier.testTag("capture-cancel")) {
                            Text(stringResource(R.string.manual_cancel))
                        }
                    }
                },
                shutter = {
                    CaptureShutter(
                        label = stringResource(R.string.continuous_capture),
                        tag = "capture-shutter",
                        enabled = (permission || backendOverride != null) && !s.paused && !s.busy && !s.finishing &&
                            (capture != null || backendOverride != null),
                    ) {
                        val camera = capture
                        val backend = backendOverride ?: { file: File, done: (Boolean) -> Unit ->
                            if (camera == null) done(false) else camera.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) = done(true)
                                    override fun onError(exception: ImageCaptureException) = done(false)
                                },
                            )
                        }
                        model.capture(backend)
                    }
                },
            )
        }
    }

    if (showGrid && s.phase == CapturePhase.REVIEW && s.pages.isNotEmpty()) {
        CapturePageGrid(
            pages = s.pages,
            selectedIndex = index.coerceIn(s.pages.indices),
            onSelected = { index = it },
            onMoveTo = { destination ->
                if (model.moveTo(index, destination)) index = destination
            },
            onRetake = {
                showGrid = false
                model.replace(index)
                model.resume()
            },
            onDismiss = { showGrid = false },
        )
    }
}

@Composable
private fun CaptureModeSelector(
    selected: CaptureMode, enabled: Boolean, onSelect: (CaptureMode) -> Unit, onCardCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
            .padding(horizontal = 8.dp).testTag("capture-modes"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in CaptureMode.entries) {
            val label = when (mode) {
                CaptureMode.DOCUMENT -> R.string.camera_mode_document
                CaptureMode.BATCH -> R.string.camera_mode_batch
                CaptureMode.BOOK -> R.string.camera_mode_book
            }
            RmeFilterChip(
                selected = selected == mode,
                enabled = enabled,
                onClick = { onSelect(mode) },
                label = { Text(stringResource(label), maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("capture-mode-${mode.name.lowercase()}"),
            )
        }
        AssistChip(
            onClick = onCardCopy,
            enabled = enabled,
            label = { Text(stringResource(R.string.scan_chooser_card_title), maxLines = 1) },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("capture-open-card-copy"),
        )
    }
}

@Composable
private fun ContinuousCameraPreview(owner:String,singlePage:Boolean,onReady:(ImageCapture?)->Unit,onUnavailable:()->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    val ready by rememberUpdatedState(onReady)
    val unavailable by rememberUpdatedState(onUnavailable)
    val view=remember(owner){PreviewView(context).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER}}
    AndroidView(factory={view},modifier=Modifier.fillMaxSize().testTag("capture-viewfinder"))
    DisposableEffect(owner,lifecycle,view) {
        var disposed=false
        var provider:ProcessCameraProvider?=null
        var preview:Preview?=null
        var image:ImageCapture?=null
        var camera:Camera?=null
        val observer=Observer<CameraState>{state->if(!disposed&&state.error!=null)unavailable()}
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!disposed)try {
                val p=future.get();provider=p
                check(p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                val rotation=view.display?.rotation?:android.view.Surface.ROTATION_0
                val pre=ContinuousCameraConfiguration.preview(rotation)
                val cap=if (singlePage) ContinuousCameraConfiguration.singlePageCapture(rotation) else ContinuousCameraConfiguration.capture(rotation)
                preview=pre;image=cap;pre.setSurfaceProvider(view.surfaceProvider)
                camera=p.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,pre,cap)
                camera!!.cameraInfo.cameraState.observe(lifecycle,observer)
                ready(cap)
            }catch(_:Exception){if(!disposed)unavailable()}
        },ContextCompat.getMainExecutor(context))
        onDispose {
            disposed=true;ready(null)
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            val useCases=listOfNotNull(preview,image).toTypedArray()
            if(useCases.isNotEmpty())provider?.unbind(*useCases)
        }
    }
}
