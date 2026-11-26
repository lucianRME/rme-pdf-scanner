package org.synapseworks.pageharbor.document.localtools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.NormalPdfExportPlan
import org.synapseworks.pageharbor.document.NormalPdfRecompositionResult
import org.synapseworks.pageharbor.document.PdfExportResult
import org.synapseworks.pageharbor.document.booklayout.BookLayoutPdfEngine
import org.synapseworks.pageharbor.document.booklayout.BookLayoutResult
import org.synapseworks.pageharbor.document.booklayout.BookLayoutSettings
import org.synapseworks.pageharbor.document.copyPdfToDestination
import org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfResult
import org.synapseworks.pageharbor.document.protectedpdf.PasswordProtectedPdf
import org.synapseworks.pageharbor.document.recomposeNormalPdf
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.document.signature.SignaturePdfRequest
import org.synapseworks.pageharbor.document.signature.SignaturePdfResult
import org.synapseworks.pageharbor.document.signature.SignaturePdfStamper

sealed interface LocalPdfToolRequest {
    data class Signature(val request: SignaturePdfRequest) : LocalPdfToolRequest
    data class Book(val settings: BookLayoutSettings) : LocalPdfToolRequest
    /** Memory only; the workflow clears this array after every outcome. */
    class Protected(val password: CharArray) : LocalPdfToolRequest
}

data class LocalPdfToolsState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val source: File? = null,
    val sourcePages: Int = 0,
    val output: File? = null,
    val outputPages: Int = 0,
    val suggestedName: String = "RME-copy.pdf",
    val message: String? = null,
)

/** Private, memory-only workflow. It never replaces a library or active-session document. */
class LocalPdfToolsViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(LocalPdfToolsState())
        private set
    private var workspace: File? = null
    private var operation: Job? = null
    private var generation = 0L
    private val saveRequests = LocalToolsSaveRequests()

    init {
        // As with existing share copies, abandoned process-death cache files have a bounded lifetime.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        viewModelScope.launch(Dispatchers.IO) {
            File(application.cacheDir, DIRECTORY).listFiles()?.forEach {
                if (it.name.startsWith("tool-") && it.lastModified() < cutoff) it.deleteRecursively()
            }
        }
    }

    /** Caller holds the DocumentSession lease until this suspension returns. */
    suspend fun open(plan: NormalPdfExportPlan) {
        close()
        val token = generation
        state = LocalPdfToolsState(visible = true, busy = true)
        val directory = File(getApplication<Application>().cacheDir, "$DIRECTORY/tool-${UUID.randomUUID()}")
        var adopted = false
        try {
            val pages = withContext(Dispatchers.IO) {
                check(directory.mkdirs())
                val input = File(directory, "input.pdf")
                val context = getApplication<Application>()
                when (plan) {
                    is NormalPdfExportPlan.DirectScannerPdf -> {
                        val uri = requireNotNull(plan.source).toAndroidUri()
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                            input.outputStream().use { out -> copyBounded(stream, out, MAX_BYTES) }
                        }
                    }
                    is NormalPdfExportPlan.RecomposeFromPages -> {
                        val prepared = recomposeNormalPdf(context, plan.pages)
                        check(prepared is NormalPdfRecompositionResult.Ready)
                        try {
                            prepared.file.inputStream().use { stream ->
                                input.outputStream().use { out -> copyBounded(stream, out, MAX_BYTES) }
                            }
                        } finally {
                            deleteNormalPdfRecomposition(prepared.file)
                        }
                    }
                }
                coroutineContext.ensureActive()
                PDFBoxResourceLoader.init(context)
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)).use { pdf ->
                    check(!pdf.isEncrypted && pdf.numberOfPages in 1..1000)
                    pdf.numberOfPages
                }
            }
            if (token != generation) return
            workspace = directory
            adopted = true
            state = LocalPdfToolsState(visible = true, source = File(directory, "input.pdf"), sourcePages = pages)
        } catch (cancelled: CancellationException) {
            if (token == generation) state = LocalPdfToolsState()
            throw cancelled
        } catch (_: Exception) {
            if (token == generation) state = LocalPdfToolsState(
                visible = true,
                message = "This document could not be prepared. Use a PDF with at most 1,000 pages and 256 MB.",
            )
        } finally {
            if (!adopted) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    fun prepare(request: LocalPdfToolRequest) {
        val source = state.source
        val directory = workspace
        if (state.busy || source == null || directory == null) {
            if (request is LocalPdfToolRequest.Protected) request.password.fill('\u0000')
            return
        }
        val token = generation
        val previousOutput = state.output
        val previousState = state
        state = state.copy(busy = true, message = null)
        operation = viewModelScope.launch {
            val output = File(directory, "copy-${UUID.randomUUID()}.pdf")
            var retained = false
            try {
                val pages = when (request) {
                    is LocalPdfToolRequest.Signature -> when (
                        val result = SignaturePdfStamper.stamp(source, output, request.request)
                    ) {
                        is SignaturePdfResult.Success -> result.pageCount
                        else -> null
                    }
                    is LocalPdfToolRequest.Book -> when (
                        val result = BookLayoutPdfEngine().transform(source, output, request.settings)
                    ) {
                        is BookLayoutResult.Success -> result.pageCount
                        else -> null
                    }
                    is LocalPdfToolRequest.Protected -> when (
                        val result = PasswordProtectedPdf().create(source, output, request.password)
                    ) {
                        is PasswordPdfResult.Success -> result.pageCount
                        else -> null
                    }
                }
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                if (pages == null) {
                    state = previousState.copy(message = "The copy could not be prepared. Check the options and try again.")
                } else {
                    previousOutput?.delete()
                    retained = true
                    val name = when (request) {
                        is LocalPdfToolRequest.Signature -> "RME-signed.pdf"
                        is LocalPdfToolRequest.Book -> "RME-book-layout.pdf"
                        is LocalPdfToolRequest.Protected -> "RME-protected.pdf"
                    }
                    state = previousState.copy(output = output, outputPages = pages, suggestedName = name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) state = previousState.copy(message = "The copy could not be prepared.")
            } finally {
                if (request is LocalPdfToolRequest.Protected) request.password.fill('\u0000')
                if (!retained) output.delete()
            }
        }.also { job ->
            // Completion handlers also run if cancellation prevented entry into the coroutine body.
            if (request is LocalPdfToolRequest.Protected) job.invokeOnCompletion {
                request.password.fill('\u0000')
            }
        }
    }

    /** Keeps this exact output busy until its picker result is consumed or the workflow closes. */
    fun createSaveRequest(): String? {
        val output = state.output ?: return null
        if (state.busy || !state.visible || !output.isFile) return null
        val request = saveRequests.create(output, generation) ?: return null
        state = state.copy(busy = true, message = null)
        return request.id
    }

    fun save(destination: Uri?, requestId: String) {
        val resolution = saveRequests.consume(requestId, generation, state.output, cancelled = destination == null)
        val request = when (resolution) {
            LocalToolsSaveResolution.Ignored -> return
            is LocalToolsSaveResolution.Cancelled -> {
                if (resolution.request.generation == generation) state = state.copy(busy = false)
                return
            }
            is LocalToolsSaveResolution.Invalidated -> {
                if (resolution.request.generation == generation) state = state.copy(
                    busy = false,
                    message = "The prepared copy changed. Choose Save again.",
                )
                return
            }
            is LocalToolsSaveResolution.Ready -> resolution.request
        }
        // A Ready resolution cannot carry a cancelled destination, but keep that invariant local.
        val target = destination ?: return
        val output = request.output
        val token = request.generation
        val previous = state.copy(busy = false, message = null)
        operation = viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    // Open the captured source first: a missing source must not truncate a target.
                    output.inputStream().use { input ->
                        coroutineContext.ensureActive()
                        val stream = getApplication<Application>().contentResolver.openOutputStream(target)
                        if (stream == null) false else {
                            stream.use { copyPdfToDestination(input, it) == PdfExportResult.Success }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (token == generation && state.output === output) state = previous.copy(
                message = if (success) "PDF copy saved." else "The PDF could not be saved. Choose another destination.",
            )
        }
    }

    fun showMessage(message: String) { state = state.copy(message = message) }

    fun discardOutput() {
        if (state.busy || state.output == null) return
        state.output?.delete()
        state = state.copy(output = null, outputPages = 0, message = null)
    }

    fun beginHandoff(): File? {
        val output = state.output ?: return null
        if (state.busy) return null
        state = state.copy(busy = true, message = null)
        return output
    }

    fun finishHandoff(output: File, message: String? = null) {
        if (state.output == output) state = state.copy(busy = false, message = message)
    }

    fun close() {
        saveRequests.invalidate()
        generation++
        val finishing = operation
        finishing?.cancel()
        operation = null
        val old = workspace
        workspace = null
        state = LocalPdfToolsState()
        if (finishing != null && !finishing.isCompleted) {
            finishing.invokeOnCompletion { old?.deleteRecursively() }
        } else {
            old?.deleteRecursively()
        }
    }

    override fun onCleared() { close() }

    private suspend fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit)
            output.write(buffer, 0, count)
        }
        check(total > 0)
    }

    private companion object {
        const val DIRECTORY = "local-pdf-tools"
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}
