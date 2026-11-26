package org.synapseworks.pageharbor.document.crop

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.booklayout.BookDerivedPage
import org.synapseworks.pageharbor.document.booklayout.BookPreviewImage
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.image.DocumentFilter

internal data class ImportCropState(
    val page: DocumentPage,
    val source: CropSource? = null,
    val crop: PageCrop = PageCrop.FULL,
    val preview: BookPreviewImage? = null,
    val enhance: Boolean = false,
    val busy: Boolean = true,
    val failed: Boolean = false,
) {
    val canApply get() = !busy && !failed && preview != null && !crop.boundsCoverFrame
    val filter get() = if (enhance) DocumentFilter.DOCUMENT_CLEANUP else page.filter
}

internal class ImportCropViewModel(app: Application) : AndroidViewModel(app) {
    private class Owner(val session: DocumentSession, val revision: Long, val release: () -> Unit) {
        var workspace: File? = null
    }

    private val processor = ImportCropProcessor(app)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var owner: Owner? = null
    var state: ImportCropState? by mutableStateOf(null)
        private set

    fun open(session: DocumentSession, revision: Long, index: Int, automatic: Boolean, release: () -> Unit): Boolean {
        val page = session.pages.getOrNull(index)?.takeIf(DocumentPage::supportsImportCrop) ?: return false
        if (owner != null) return false
        val review = Owner(session, revision, release)
        owner = review
        state = ImportCropState(page)
        runWork(review) {
            val workspace = processor.begin().also { review.workspace = it }
            val source = processor.analyze(workspace, page)
            if (owner !== review) return@runWork
            if (automatic && source.proposal == null) {
                close()
                return@runWork
            }
            val crop = source.proposal ?: PageCrop.FULL
            val preview = if (source.proposal == null) null else processor.preview(workspace, page, source, crop)
            if (owner === review) {
                state = ImportCropState(page, source, crop, preview,
                    enhance = preview != null && page.filter == DocumentFilter.ORIGINAL, busy = false)
            }
        }
        return true
    }

    fun updateCorners(crop: PageCrop) {
        val review = owner ?: return
        val current = state?.takeUnless { it.busy } ?: return
        val source = current.source ?: return
        state = current.copy(crop = crop, busy = true, failed = false, preview = null)
        runWork(review) {
            val preview = if (crop.boundsCoverFrame) null else processor.preview(
                requireNotNull(review.workspace), current.page, source, crop,
            )
            if (owner === review) state = state?.copy(preview = preview, busy = false)
        }
    }

    fun toggleEnhancement() {
        val current = state?.takeUnless { it.busy || it.page.filter != DocumentFilter.ORIGINAL } ?: return
        state = current.copy(enhance = !current.enhance)
    }

    fun apply(commit: (DocumentSession, Long, DocumentPage, BookDerivedPage, DocumentFilter) -> Boolean) {
        val review = owner ?: return
        val current = state?.takeIf { it.canApply } ?: return
        state = current.copy(busy = true)
        runWork(review) {
            val derived = processor.prepare(requireNotNull(review.workspace), current.page,
                requireNotNull(current.source), current.crop)
            if (owner !== review) return@runWork

            // Publishing advances the revision and can wake observers inline. Detach the
            // old review first so its revision callback cannot discard the transferred page.
            val pending = task
            owner = null
            task = null
            state = null
            val producedName = File(requireNotNull(derived.source.ownedTemporaryFile).path).name
            var transferred = false
            try {
                transferred = commit(review.session, review.revision, current.page, derived, current.filter)
            } finally {
                finish(review, pending, if (transferred) setOf(producedName) else emptySet())
            }
        }
    }

    private fun runWork(review: Owner, block: suspend () -> Unit) {
        task = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner === review) state = state?.copy(busy = false, failed = true)
            } catch (_: OutOfMemoryError) {
                if (owner === review) state = state?.copy(busy = false, failed = true)
            }
        }
    }

    fun onDocumentRevisionChanged(revision: Long) {
        if (owner?.let { it.revision != revision } == true) close()
    }

    fun close() {
        val review = owner ?: return
        val pending = task
        owner = null
        task = null
        state = null
        pending?.cancel()
        finish(review, pending, emptySet())
    }

    private fun finish(review: Owner, pending: Job?, keep: Set<String>) {
        cleanup.launch {
            try {
                pending?.join()
                review.workspace?.let { processor.finish(it, keep) }
            } finally {
                withContext(Dispatchers.Main.immediate) { review.release() }
            }
        }
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }
}
