package org.synapseworks.pageharbor.table

import org.synapseworks.pageharbor.businesscard.CardImage
import org.synapseworks.pageharbor.businesscard.CardRecognition

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No SavedStateHandle, path, bitmap, OCR persistence or contact-provider access. */
internal class TableViewModel(app: Application) : AndroidViewModel(app) {
    val activityResultOwner = UUID.randomUUID().toString()
    private val controller = TableController()
    var workbookInput = org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); private set
    private val work = CardRecognition.work
    private var job: Job? = null
    private var saveJob:Job?=null
    private var deferredSave:Triple<TableSaveToken,Uri,()->Boolean>?=null
    private var permission: TableToken? = null
    private var deferred: Pair<TableToken, Uri>? = null
    private val capturing = AtomicBoolean()
    var state: TableState? by mutableStateOf(null); private set
    private fun publish() { state = controller.state }
    fun open() { if(controller.state==null)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); controller.open(); publish() }
    fun close() { workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); controller.close(); job?.cancel(); saveJob?.cancel(); deferredSave=null; deferred = null; publish() }
    fun reset() { val previous=controller.state?.epoch; controller.reset(); if(controller.state?.epoch!=previous)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); job?.cancel(); saveJob?.cancel(); deferredSave=null; deferred = null; publish() }
    fun manual() { val previous=controller.state?.epoch; controller.manual(); if(controller.state?.epoch!=previous)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); publish() }
    fun setActive(active: Boolean) {
        controller.setActive(active)
        if(active) deferred?.let { deferred = null; import(it.first, it.second) }
        if(active) deferredSave?.let { deferredSave=null;write(it.first,it.second,it.third) }
    }
    fun background(recreating: Boolean) {
        controller.background(recreating); if(controller.state?.phase !in setOf(TablePhase.EDIT,TablePhase.REVIEW))workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); job?.cancel(); saveJob?.cancel(); if(controller.state?.phase!=TablePhase.WAITING)deferredSave=null; deferred = null; publish()
    }
    fun lock() {
        workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry")
        controller.lock(deferred != null); job?.cancel(); saveJob?.cancel(); deferredSave=null; publish()
        if(state == null) deferred = null
    }
    fun pause(owner: String, epoch: Long) { controller.pause(owner, epoch, "Camera unavailable. Retry, choose an image or enter fields manually."); publish() }
    fun beginPermission(owner: String, epoch: Long): Boolean {
        val t = TableToken(owner, epoch)
        if(permission != null || !controller.matches(t, TablePhase.CAMERA)) return false
        permission = t; return true
    }
    fun permissionResult(allowed: Boolean) {
        val t = permission ?: return; permission = null
        if(!controller.matches(t, TablePhase.CAMERA)) return
        if(allowed) reset() else { controller.pause(t.owner, t.epoch, "Camera access denied. Choose an image, enter fields manually, or allow access in Android."); publish() }
    }
    fun beginPicker(owner: String, epoch: Long): Boolean {
        val accepted = controller.beginPicker(owner, epoch)
        if(accepted) { job?.cancel(); deferred = null }
        publish(); return accepted
    }
    fun picked(uri: Uri?) {
        val t = controller.picked(uri != null); publish()
        if(t != null && uri != null) {
            if(controller.matches(t, TablePhase.OCR)) import(t, uri) else deferred = t to uri
        }
    }
    private fun import(token: TableToken, uri: Uri) {
        if(!controller.matches(token, TablePhase.OCR)) return
        job = viewModelScope.launch {
            var input=org.synapseworks.pageharbor.xlsx.XlsxInput("Unverified image")
            val result = recognize(token) { check ->
                val before=org.synapseworks.pageharbor.xlsx.XlsxInput.image(getApplication(),uri,check)
                val parsed=recognizeTable({ getApplication<Application>().contentResolver.openInputStream(uri) },check=check)
                val after=org.synapseworks.pageharbor.xlsx.XlsxInput.image(getApplication(),uri,check)
                input=if(before==after)before else org.synapseworks.pageharbor.xlsx.XlsxInput("Unverified image")
                parsed
            }
            if(controller.matches(token,TablePhase.OCR))workbookInput=input
            controller.complete(token, result); publish()
        }
    }
    private fun recognizeTable(open: () -> java.io.InputStream?, rotation: Int? = null, check: () -> Unit): TableExtraction? {
        return TableRecognition.recognize(open,rotation,check)
    }
    private suspend fun recognize(token: TableToken, operation: ((() -> Unit)) -> TableExtraction?): TableExtraction? =
        try { withContext(Dispatchers.IO) { work.withLock {
            val context = currentCoroutineContext()
            operation { context.ensureActive(); if(!controller.matches(token, TablePhase.OCR)) throw CancellationException("Table session ended") }
        } } } catch(e: CancellationException) { throw e } catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
    fun beginCapture(owner: String, epoch: Long): TableToken? {
        if(!capturing.compareAndSet(false, true)) return null
        val token = controller.capture(owner, epoch)
        if(token == null) capturing.set(false)
        publish(); return token
    }
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun captured(token: TableToken, image: CardImage) {
        if(!controller.captured(token)) { try { image.close() } finally { capturing.set(false) }; return }
        workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Camera capture")
        publish()
        // ATOMIC guarantees finally executes even if close/lock cancels before coroutine dispatch.
        job = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val result = try { recognize(token) { recognizeTable(image::open, image.rotation, it) } }
                finally { try { image.close() } finally { capturing.set(false) } }
            controller.complete(token, result); publish()
        }
    }
    fun captureFailed(token: TableToken) {
        capturing.set(false); controller.pause(token.owner, token.epoch, "The table photo could not be captured. Retry or choose an image."); publish()
    }
    fun edit(owner:String,epoch:Long,id:String,value:String){controller.edit(owner,epoch,id,value);publish()}
    fun layout(owner:String,epoch:Long,update:(TableGrid)->TableGrid){controller.layout(owner,epoch,update);publish()}
    fun review(owner:String,epoch:Long){controller.review(owner,epoch);publish()}
    fun correct(){controller.correct();publish()}
    fun confirm(owner:String,epoch:Long,id:String,value:Boolean){controller.confirm(owner,epoch,id,value);publish()}
    fun beginSave(owner:String,epoch:Long,id:String):Boolean=controller.beginSave(owner,epoch,id).also{publish()}
    fun pickerFailed(){controller.pickerFailed();publish()}
    fun cancelExport(){controller.cancelExport();saveJob?.cancel();deferredSave=null;publish()}
    fun destination(uri:Uri?,allowed:()->Boolean={true}){
        if(uri!=null&&uri.scheme!="content"){controller.rejectedDestination();publish();return}
        val token=controller.destination(uri!=null);publish()
        if(token!=null&&uri!=null){if(!write(token,uri,allowed))deferredSave=Triple(token,uri,allowed)}
    }
    private fun write(token:TableSaveToken,uri:Uri,allowed:()->Boolean):Boolean{
        val output=controller.startWrite(token)?:return false;publish()
        saveJob=viewModelScope.launch {
            val success=try{withContext(Dispatchers.IO){TableCsvWriter.work.withLock{
                val context=currentCoroutineContext()
                output.write({getApplication<Application>().contentResolver.openOutputStream(uri,"wt")}){
                    context.ensureActive();if(!allowed()||!controller.canWrite(token))throw CancellationException("Table export ended")
                }
            }}}catch(e:CancellationException){controller.saved(token,false);publish();throw e}catch(_:Exception){false}catch(_:OutOfMemoryError){false}
            controller.saved(token,success);publish()
        };return true
    }
    override fun onCleared(){close();super.onCleared()}
}
