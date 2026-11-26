package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Alignment
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import org.synapseworks.pageharbor.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.document.capture.CameraAccess
import org.synapseworks.pageharbor.qr.*
import org.synapseworks.pageharbor.ui.copyPlainTextToClipboard

@Composable internal fun QrScreen(model: QrViewModel,onChooseImage:(String,Long)->Unit,onAllowCamera:(String,Long)->Unit) {
    val s=model.state ?: return
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    var permission by remember(s.owner,s.generation){mutableStateOf(CameraAccess.allowed(context))}
    DisposableEffect(s.owner,s.generation,lifecycle){
        var watching=true
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)permission=CameraAccess.allowed(context)}
        lifecycle.lifecycle.addObserver(observer)
        val ops=context.getSystemService(android.app.AppOpsManager::class.java)
        val listener=android.app.AppOpsManager.OnOpChangedListener{op,pkg->
            if((op==null||op==android.app.AppOpsManager.OPSTR_CAMERA)&&(pkg==null||pkg==context.packageName)){
                ContextCompat.getMainExecutor(context).execute{
                    if(watching && model.state?.owner==s.owner){permission=CameraAccess.allowed(context);if(!permission && model.state?.phase==QrPhase.CAMERA)model.pause(QrProblem.PERMISSION)}
                }
            }
        }
        try{ops?.startWatchingMode(android.app.AppOpsManager.OPSTR_CAMERA,context.packageName,listener)}catch(_:SecurityException){permission=false;model.pause(QrProblem.PERMISSION)}
        onDispose{watching=false;lifecycle.lifecycle.removeObserver(observer);runCatching{ops?.stopWatchingMode(listener)}}
    }
    BackHandler{if(s.linkReview)model.dismissLink()else model.close()}
    Surface(Modifier.fillMaxSize().testTag("qr-screen")){
        val live = permission && s.phase == QrPhase.CAMERA
        val pausedByUser = permission && s.phase == QrPhase.PAUSED && s.problem == null
        if (live || pausedByUser) {
            DocumentCameraLayout(
                guide = ScanGuide.QR,
                cameraActive = live,
                viewfinder = { modifier ->
                    if (live) QrCamera(model, s.owner, s.generation, modifier)
                    else Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.qr_paused)) }
                },
                controls = { modifier ->
                    LazyColumn(modifier.testTag("qr-list")) {
                        item { Text(stringResource(R.string.qr_automatic), style = MaterialTheme.typography.titleMedium) }
                        item { RmeTextButton({ onChooseImage(s.owner, s.generation) }, Modifier.testTag("qr-image")) { Text("Choose QR image") } }
                        item { RmeTextButton(model::close, Modifier.testTag("qr-close")) { Text("Back to library") } }
                    }
                },
                shutter = {
                    CaptureShutter(
                        stringResource(if (live) R.string.qr_pause else R.string.qr_resume_capture),
                        if (live) "qr-pause" else "qr-resume", true,
                        if (live) Icons.Default.Pause else Icons.Default.PlayArrow,
                    ) { if (live) model.pause() else model.rescan() }
                },
            )
        } else LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("qr-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("Scan QR code",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})}
            item{Text(stringResource(R.string.qr_capture_hint))}
            if(s.result!=null){
                item{Text("Decoded content",style=MaterialTheme.typography.titleMedium)}
                item{Text(s.result.display,Modifier.testTag("qr-content"))}
                item{Text("Controls and invisible formatting are shown as escaped code points. Copy uses the original decoded text. The OS clipboard can keep what you copy after leaving this screen.")}
                item{RmePrimaryButton(onClick={model.copy(s.owner,s.generation){text->
                    check(copyPlainTextToClipboard(context, "QR content", text)) { "Clipboard unavailable" }
                }},modifier=Modifier.fillMaxWidth().testTag("qr-copy")){Text("Copy content")}}
                if(s.copied)item{Text("Copied.",Modifier.testTag("qr-copied").semantics{liveRegion=LiveRegionMode.Polite})}
                s.result.destination?.let{destination->
                    item{Text("Web destination (unverified):")}
                    item{Text(destination.value,Modifier.testTag("qr-destination"))}
                    item{RmeSecondaryButton(onClick={model.reviewLink(s.owner,s.generation)},modifier=Modifier.fillMaxWidth().testTag("qr-review-link")){Text("Review web link…")}}
                } ?: item{Text("Text only. This payload is not an eligible HTTP(S) destination.",Modifier.testTag("qr-text-only"))}
                item{RmePrimaryButton(onClick=model::rescan,modifier=Modifier.fillMaxWidth().testTag("qr-rescan")){Text("Rescan")}}
            }else{
                when(s.phase){
                    QrPhase.CAMERA->if(!permission){
                        item{Text(stringResource(R.string.qr_permission_hint),Modifier.testTag("qr-permission-message"))}
                        item{RmePrimaryButton(onClick={onAllowCamera(s.owner,s.generation)},modifier=Modifier.fillMaxWidth().testTag("qr-permission")){Text("Allow camera")}}
                    }
                    QrPhase.PICKER->item{Text("Waiting for image selection…",Modifier.testTag("qr-waiting"))}
                    QrPhase.IMPORTING->item{Text("Decoding selected image…",Modifier.testTag("qr-working").semantics{liveRegion=LiveRegionMode.Polite})}
                    QrPhase.PAUSED->item{RmePrimaryButton(onClick=model::rescan,modifier=Modifier.fillMaxWidth().testTag("qr-resume")){Text("Resume scanning")}}
                    else->Unit
                }
            }
            s.problem?.let{p->item{Text(p.message(),Modifier.testTag("qr-error"),color=MaterialTheme.colorScheme.error)}}
            item{RmeSecondaryButton(onClick={onChooseImage(s.owner,s.generation)},enabled=s.phase !in setOf(QrPhase.PICKER,QrPhase.IMPORTING),modifier=Modifier.fillMaxWidth().testTag("qr-image")){Text("Choose QR image")}}
            item{Text(stringResource(R.string.qr_result_lifetime))}
            item{RmeTextButton(onClick=model::close,modifier=Modifier.fillMaxWidth().testTag("qr-close")){Text("Back to library")}}
        }
    }
    if(s.linkReview)s.result?.destination?.let{destination->
        androidx.compose.ui.window.Dialog(onDismissRequest=model::dismissLink,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
            Surface(Modifier.fillMaxSize()){
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("qr-link-list"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Text("Open this web destination?",style=MaterialTheme.typography.headlineSmall)}
                    item{Text(destination.value,Modifier.testTag("qr-link-address"))}
                    item{Text("This is the actual address handed to Android. Its content and owner are not verified. Your selected external app may use the network. Nothing is sent until you tap Open web link.")}
                    item{RmePrimaryButton(onClick={model.openLink(s.owner,s.generation){value->
                        try{val checked=QrDestination.parse(value)
                            if(checked?.value!=value)false else{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(value)).addCategory(Intent.CATEGORY_BROWSABLE));true}
                        }catch(_:Exception){false}
                    }},modifier=Modifier.fillMaxWidth().testTag("qr-open-link")){Text("Open web link")}}
                    item{RmeTextButton(onClick=model::dismissLink,modifier=Modifier.fillMaxWidth().testTag("qr-link-cancel")){Text("Cancel")}}
                }
            }
        }
    }
}
private fun QrProblem.message()=when(this){
    QrProblem.PERMISSION->"Camera access is unavailable. Allow it in Android or choose an image."
    QrProblem.CAMERA->"The back camera could not be used. Retry scanning or choose an image."
    QrProblem.IMAGE->"The image could not be decoded within the supported input limits. Choose a different image."
    QrProblem.NO_CODE->"No readable QR code was found. Try a clearer image or resume scanning."
    QrProblem.HANDOFF->"The requested action could not be completed."
    QrProblem.PICKER_PENDING->"An earlier image selection is still pending. Return from that picker before choosing another image."
}
