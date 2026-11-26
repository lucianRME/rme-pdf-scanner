package org.synapseworks.pageharbor.qr

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only ephemeral metadata/text is retained. No SavedStateHandle, Room, backup, frame or bitmap. */
internal class QrViewModel(app: Application): AndroidViewModel(app) {
    val activityResultOwner=java.util.UUID.randomUUID().toString()
    private var permissionTicket:QrTicket?=null
    private val controller=QrController()
    private val work=Mutex()
    private val main=Handler(Looper.getMainLooper())
    private var job: Job?=null
    private var deferred: Pair<QrTicket,Uri>?=null
    var state: QrState? by mutableStateOf(null);private set
    val frames=QrFrameProcessor(work,controller,{ticket,result->main.post{completeFrame(ticket,result)}})
    private fun publish(){state=controller.state}
    fun open(){controller.open();publish()}
    fun setActive(value: Boolean){controller.active(value);if(value)deferred?.let{deferred=null;import(it.first,it.second)}}
    fun background(recreating: Boolean){controller.background(recreating);job?.cancel();job=null;deferred=null;publish()}
    fun lock(){
        controller.active(false)
        // A user-launched external picker can trigger immediate app lock. Retain only that
        // opaque request/selected URI behind authentication; never decode or retain a result.
        if(controller.state?.phase==QrPhase.PICKER || (deferred!=null && controller.state?.phase==QrPhase.IMPORTING))publish()
        else close()
    }
    fun close(){controller.close();job?.cancel();job=null;deferred=null;publish()}
    fun rescan(){job?.cancel();job=null;deferred=null;controller.rescan();publish()}
    fun pause(problem: QrProblem?=null){controller.pause(problem);publish()}
    fun cameraProblem(owner:String,generation:Long){
        if(state?.let{it.owner==owner && it.generation==generation && it.phase==QrPhase.CAMERA}==true)pause(QrProblem.CAMERA)
    }
    fun beginPicker(owner:String?=null,generation:Long?=null): Boolean {
        val ticket=controller.beginPicker(owner,generation)
        if(ticket!=null){job?.cancel();job=null;deferred=null}
        publish();return ticket!=null
    }
    fun beginPermission(owner:String,generation:Long):Boolean {
        if(permissionTicket!=null || !controller.cameraAction(owner,generation))return false
        permissionTicket=QrTicket(owner,generation,0);return true
    }
    fun permissionResult(allowed:Boolean){
        val ticket=permissionTicket?:return;permissionTicket=null
        if(!controller.cameraAction(ticket.owner,ticket.generation))return
        if(allowed)rescan()else pause(QrProblem.PERMISSION)
    }
    fun receivePickedImage(uri: Uri?){
        val ticket=controller.receivePicker(uri!=null);publish()
        if(ticket!=null && uri!=null){
            if(controller.currentImport(ticket))import(ticket,uri) else deferred=ticket to uri
        }
    }
    private fun import(ticket: QrTicket, uri: Uri){
        if(!controller.currentImport(ticket))return
        job=viewModelScope.launch {
            val result=try {
                withContext(Dispatchers.IO){work.withLock {
                    val context=currentCoroutineContext()
                    QrImageReader.read({getApplication<Application>().contentResolver.openInputStream(uri)}){
                        context.ensureActive();if(!controller.currentImport(ticket))throw CancellationException("QR scan ended")
                    }
                }}
            }catch(e: CancellationException){throw e}catch(_: Exception){QrDecode.Invalid}
            controller.completeImport(ticket,result);publish()
        }
    }
    internal fun frameTicket(): QrTicket?=controller.frame()
    internal fun completeFrame(ticket: QrTicket,result: QrDecode){controller.completeFrame(ticket,result);publish()}
    fun reviewLink(owner: String, epoch: Long){controller.reviewLink(owner,epoch);publish()}
    fun dismissLink(){controller.dismissLink();publish()}
    fun copy(owner: String,epoch: Long,action:(String)->Unit){controller.result(owner,epoch)?.let{try{action(it.text);controller.copied(owner,epoch)}catch(_:Exception){controller.handoffFailed()};publish()}}
    fun openLink(owner: String,epoch: Long,action:(String)->Boolean){
        val destination=controller.destination(owner,epoch) ?: return
        if(!action(destination.value))controller.handoffFailed()else controller.dismissLink()
        publish()
    }
    override fun onCleared(){close();super.onCleared()}
}
