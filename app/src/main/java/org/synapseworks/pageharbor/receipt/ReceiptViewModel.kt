package org.synapseworks.pageharbor.receipt

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
internal class ReceiptViewModel(app: Application) : AndroidViewModel(app) {
    val activityResultOwner = UUID.randomUUID().toString()
    private val controller = ReceiptController()
    var workbookInput = org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); private set
    private val work = CardRecognition.work
    private var job: Job? = null
    private var permission: ReceiptToken? = null
    private var deferred: Pair<ReceiptToken, Uri>? = null
    private val capturing = AtomicBoolean()
    var state: ReceiptState? by mutableStateOf(null); private set
    private fun publish() { state = controller.state }
    fun open() { if(controller.state==null)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); controller.open(); publish() }
    fun close() { workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); controller.close(); job?.cancel(); deferred = null; publish() }
    fun reset() { val previous=controller.state?.epoch; controller.reset(); if(controller.state?.epoch!=previous)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); job?.cancel(); deferred = null; publish() }
    fun manual() { val previous=controller.state?.epoch; controller.manual(); if(controller.state?.epoch!=previous)workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); publish() }
    fun setActive(active: Boolean) {
        controller.setActive(active)
        if(active) deferred?.let { deferred = null; import(it.first, it.second) }
    }
    fun background(recreating: Boolean) {
        controller.background(recreating); if(controller.state?.phase !in setOf(ReceiptPhase.EDIT,ReceiptPhase.REVIEW))workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry"); job?.cancel(); deferred = null; publish()
    }
    fun lock() {
        workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Manual entry")
        controller.lock(deferred != null); job?.cancel(); publish()
        if(state == null) deferred = null
    }
    fun pause(owner: String, epoch: Long) { controller.pause(owner, epoch, "Camera unavailable. Retry, choose an image or enter fields manually."); publish() }
    fun beginPermission(owner: String, epoch: Long): Boolean {
        val t = ReceiptToken(owner, epoch)
        if(permission != null || !controller.matches(t, ReceiptPhase.CAMERA)) return false
        permission = t; return true
    }
    fun permissionResult(allowed: Boolean) {
        val t = permission ?: return; permission = null
        if(!controller.matches(t, ReceiptPhase.CAMERA)) return
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
            if(controller.matches(t, ReceiptPhase.OCR)) import(t, uri) else deferred = t to uri
        }
    }
    private fun import(token: ReceiptToken, uri: Uri) {
        if(!controller.matches(token, ReceiptPhase.OCR)) return
        job = viewModelScope.launch {
            var input=org.synapseworks.pageharbor.xlsx.XlsxInput("Unverified image")
            val result = recognize(token) { check ->
                val before=org.synapseworks.pageharbor.xlsx.XlsxInput.image(getApplication(),uri,check)
                val parsed=recognizeReceipt({ getApplication<Application>().contentResolver.openInputStream(uri) },check=check)
                val after=org.synapseworks.pageharbor.xlsx.XlsxInput.image(getApplication(),uri,check)
                input=if(before==after)before else org.synapseworks.pageharbor.xlsx.XlsxInput("Unverified image")
                parsed
            }
            if(controller.matches(token,ReceiptPhase.OCR))workbookInput=input
            controller.complete(token, result); publish()
        }
    }
    private fun recognizeReceipt(open: () -> java.io.InputStream?, rotation: Int? = null, check: () -> Unit): ReceiptExtraction? {
        val page = CardRecognition.recognizePage(open,rotation,check) ?: return null
        return ReceiptParser.parse(page.text,page.layout,check=check)
    }
    private suspend fun recognize(token: ReceiptToken, operation: ((() -> Unit)) -> ReceiptExtraction?): ReceiptExtraction? =
        try { withContext(Dispatchers.IO) { work.withLock {
            val context = currentCoroutineContext()
            operation { context.ensureActive(); if(!controller.matches(token, ReceiptPhase.OCR)) throw CancellationException("Receipt session ended") }
        } } } catch(e: CancellationException) { throw e } catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
    fun beginCapture(owner: String, epoch: Long): ReceiptToken? {
        if(!capturing.compareAndSet(false, true)) return null
        val token = controller.capture(owner, epoch)
        if(token == null) capturing.set(false)
        publish(); return token
    }
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun captured(token: ReceiptToken, image: CardImage) {
        if(!controller.captured(token)) { try { image.close() } finally { capturing.set(false) }; return }
        workbookInput=org.synapseworks.pageharbor.xlsx.XlsxInput("Camera capture")
        publish()
        // ATOMIC guarantees finally executes even if close/lock cancels before coroutine dispatch.
        job = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val result = try { recognize(token) { recognizeReceipt(image::open, image.rotation, it) } }
                finally { try { image.close() } finally { capturing.set(false) } }
            controller.complete(token, result); publish()
        }
    }
    fun captureFailed(token: ReceiptToken) {
        capturing.set(false); controller.pause(token.owner, token.epoch, "The receipt photo could not be captured. Retry or choose an image."); publish()
    }
    fun edit(owner: String, epoch: Long, field: ReceiptField, value: String) { controller.edit(owner,epoch,field,value); publish() }
    fun rules(owner: String, epoch: Long, rules: ReceiptRules) { controller.rules(owner,epoch,rules); publish() }
    fun review(owner: String, epoch: Long) { controller.review(owner,epoch); publish() }
    fun correct() { controller.correct(); publish() }
    override fun onCleared() { close(); super.onCleared() }
}
