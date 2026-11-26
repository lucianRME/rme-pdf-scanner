package org.synapseworks.pageharbor.businesscard

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
internal class CardViewModel(app: Application) : AndroidViewModel(app) {
    val activityResultOwner = UUID.randomUUID().toString()
    private val controller = CardController()
    private val work = CardRecognition.work
    private var job: Job? = null
    private var permission: CardToken? = null
    private var deferred: Pair<CardToken, Uri>? = null
    private val capturing = AtomicBoolean()
    var state: CardState? by mutableStateOf(null); private set
    private fun publish() { state = controller.state }
    fun open() { controller.open(); publish() }
    fun close() { controller.close(); job?.cancel(); deferred = null; publish() }
    fun reset() { controller.reset(); job?.cancel(); deferred = null; publish() }
    fun manual() { controller.manual(); publish() }
    fun setActive(active: Boolean) {
        controller.setActive(active)
        if(active) deferred?.let { deferred = null; import(it.first, it.second) }
    }
    fun background(recreating: Boolean) {
        controller.background(recreating); job?.cancel(); deferred = null; publish()
    }
    fun lock() {
        controller.lock(deferred != null); job?.cancel(); publish()
        if(state == null) deferred = null
    }
    fun pause(owner: String, epoch: Long) { controller.pause(owner, epoch, "Camera unavailable. Retry, choose an image or enter fields manually."); publish() }
    fun beginPermission(owner: String, epoch: Long): Boolean {
        val t = CardToken(owner, epoch)
        if(permission != null || !controller.matches(t, CardPhase.CAMERA)) return false
        permission = t; return true
    }
    fun permissionResult(allowed: Boolean) {
        val t = permission ?: return; permission = null
        if(!controller.matches(t, CardPhase.CAMERA)) return
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
            if(controller.matches(t, CardPhase.OCR)) import(t, uri) else deferred = t to uri
        }
    }
    private fun import(token: CardToken, uri: Uri) {
        if(!controller.matches(token, CardPhase.OCR)) return
        job = viewModelScope.launch {
            val result = recognize(token) { CardRecognition.recognize({ getApplication<Application>().contentResolver.openInputStream(uri) }, check = it) }
            controller.complete(token, result); publish()
        }
    }
    private suspend fun recognize(token: CardToken, operation: ((() -> Unit)) -> ContactSuggestions?): ContactSuggestions? =
        try { withContext(Dispatchers.IO) { work.withLock {
            val context = currentCoroutineContext()
            operation { context.ensureActive(); if(!controller.matches(token, CardPhase.OCR)) throw CancellationException("Card session ended") }
        } } } catch(e: CancellationException) { throw e } catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
    fun beginCapture(owner: String, epoch: Long): CardToken? {
        if(!capturing.compareAndSet(false, true)) return null
        val token = controller.capture(owner, epoch)
        if(token == null) capturing.set(false)
        publish(); return token
    }
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun captured(token: CardToken, image: CardImage) {
        if(!controller.captured(token)) { try { image.close() } finally { capturing.set(false) }; return }
        publish()
        // ATOMIC guarantees finally executes even if close/lock cancels before coroutine dispatch.
        job = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            val result = try { recognize(token) { CardRecognition.recognize(image::open, image.rotation, it) } }
                finally { try { image.close() } finally { capturing.set(false) } }
            controller.complete(token, result); publish()
        }
    }
    fun captureFailed(token: CardToken) {
        capturing.set(false); controller.pause(token.owner, token.epoch, "The card photo could not be captured. Retry or choose an image."); publish()
    }
    fun edit(owner: String, epoch: Long, field: ContactField, index: Int, value: String) {
        controller.edit(owner, epoch) { it.edit(field, index, value) }; publish()
    }
    fun add(field: ContactField) { state?.let { s -> controller.edit(s.owner, s.epoch) { it.add(field) }; publish() } }
    fun remove(field: ContactField, index: Int) { state?.let { s -> controller.edit(s.owner, s.epoch) { it.remove(field, index) }; publish() } }
    fun candidate(owner: String, epoch: Long, field: ContactField, value: String) {
        controller.edit(owner, epoch) { draft ->
            if(field == ContactField.ADDRESS) {
                val empty = draft.addresses.indexOfFirst(String::isBlank)
                val next = if(empty < 0) draft.add(field) else draft
                val target = if(empty < 0) draft.addresses.size else empty
                next.edit(field, target, value)
            } else draft.edit(field, 0, value)
        }; publish()
    }
    fun review(owner: String, epoch: Long) { controller.review(owner, epoch); publish() }
    fun correct() { controller.correct(); publish() }
    fun handoff(owner: String, epoch: Long, launch: (android.content.Intent) -> Unit) {
        val draft = controller.handoff(owner, epoch) ?: return; publish()
        val intent = ContactEditorIntent.create(draft)
        try { if(intent == null) controller.editorFailed() else launch(intent) } catch(_: Exception) { controller.editorFailed() }
        publish()
    }
    fun editorReturned() { controller.editorReturned(); publish() }
    override fun onCleared() { close(); super.onCleared() }
}
