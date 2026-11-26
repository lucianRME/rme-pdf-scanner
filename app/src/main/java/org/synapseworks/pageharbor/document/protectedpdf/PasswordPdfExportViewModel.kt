package org.synapseworks.pageharbor.document.protectedpdf

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
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.NormalPdfExportPlan
import org.synapseworks.pageharbor.document.NormalPdfVisualStreamProvider
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.localtools.LocalToolsSaveRequests
import org.synapseworks.pageharbor.document.localtools.LocalToolsSaveResolution
import org.synapseworks.pageharbor.document.session.toAndroidUri

enum class PasswordPdfWork { CAPTURING, ENCRYPTING, PICKER, SAVING, SHARING }

data class PasswordPdfExportState(
    val visible: Boolean = false,
    val work: PasswordPdfWork? = null,
    val source: File? = null,
    val sourcePages: Int = 0,
    val output: File? = null,
    val message: Int? = null,
) {
    val busy: Boolean get() = work != null
}

data class PasswordPdfHandoff internal constructor(internal val output: File, internal val generation: Long)

/** M05-only extraction of the existing captured-input and one-shot Save workflow. */
class PasswordPdfExportViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(PasswordPdfExportState())
        private set
    private var workspace: File? = null
    private var operation: Job? = null
    private var capture: Job? = null
    private var generation = 0L
    private val saves = LocalToolsSaveRequests()
    internal var encryptForTest: (suspend (File, File, CharArray) -> PasswordPdfResult)? = null

    init {
        // No workspace can resume after process death. Remove abandoned plaintext on the next
        // host creation; the process-local owner set protects live/recreated workflows.
        viewModelScope.launch(Dispatchers.IO) {
            File(application.cacheDir, DIRECTORY).listFiles()?.forEach { file ->
                if (file.name.startsWith("protect-") && file !in active) {
                    file.deleteRecursively()
                }
            }
        }
    }

    /** The Activity owns a session lease until this call returns, even on cancellation. */
    suspend fun open(plan: NormalPdfExportPlan) {
        close()
        val token = generation
        val caller = coroutineContext[Job]
        capture = caller
        state = PasswordPdfExportState(visible = true, work = PasswordPdfWork.CAPTURING)
        val directory = File(getApplication<Application>().cacheDir, "$DIRECTORY/protect-${UUID.randomUUID()}")
        active.add(directory)
        var adopted = false
        try {
            val pages = withContext(Dispatchers.IO) {
                check(directory.mkdirs())
                if (directory.usableSpace < 64L * 1024 * 1024) throw CaptureStorageFailure()
                val input = File(directory, "input.pdf")
                val context = getApplication<Application>()
                when (plan) {
                    is NormalPdfExportPlan.DirectScannerPdf -> {
                        val uri = requireNotNull(plan.source).toAndroidUri()
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                            input.outputStream().use { out ->
                                copyBounded(stream, out, MAX_BYTES) {
                                    if (directory.usableSpace < 64L * 1024 * 1024) throw CaptureStorageFailure()
                                    true
                                }
                            }
                        }
                    }
                    is NormalPdfExportPlan.RecomposeFromPages -> {
                        require(plan.pages.size in 1..1000)
                        // Reuse normal page rendering, but place every intermediate under our owner.
                        // Even OOM/cancellation before result delivery is cleaned with this workspace.
                        val provider = NormalPdfVisualStreamProvider(context, directory)
                        val prepared = PdfBoxSearchablePdfGenerator(context).generate(SearchablePdfRequest(
                            pages = plan.pages.map { page -> SearchablePdfPage({ provider.open(page) },paperSize=org.synapseworks.pageharbor.document.physicalPdfPageSize(page.paperSize,page.rotation)) },
                            outputFile = input,
                            scratchDirectory = directory,
                        ))
                        check(prepared is SearchablePdfGenerationResult.Success)
                        require(input.length() in 1..MAX_BYTES)
                    }
                }
                coroutineContext.ensureActive()
                PDFBoxResourceLoader.init(context)
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly().setTempDir(directory)).use {
                    require(!it.isEncrypted && it.numberOfPages in 1..1000 && it.signatureDictionaries.isEmpty())
                    it.numberOfPages
                }
            }
            if (token != generation) return
            workspace = directory
            adopted = true
            state = PasswordPdfExportState(visible = true, source = File(directory, "input.pdf"), sourcePages = pages)
        } catch (cancelled: CancellationException) {
            if (token == generation) state = PasswordPdfExportState()
            throw cancelled
        } catch (_: CaptureStorageFailure) {
            if (token == generation) state = PasswordPdfExportState(visible = true, message = R.string.protect_storage_failure)
        } catch (_: OutOfMemoryError) {
            if (token == generation) state = PasswordPdfExportState(visible = true, message = R.string.protect_resource_failure)
        } catch (_: Exception) {
            if (token == generation) state = PasswordPdfExportState(visible = true, message = R.string.protect_source_failure)
        } finally {
            if (capture === caller) capture = null
            if (!adopted) withContext(NonCancellable + Dispatchers.IO) { releaseDirectory(directory) }
        }
    }

    /** Synchronously take an owned copy and wipe the caller's mutable buffer, including rejection. */
    fun prepare(password: CharArray) {
        val owned = password.copyOf()
        password.fill('\u0000')
        val source = state.source
        val directory = workspace
        if (state.busy || source == null || directory == null || !state.visible) {
            owned.fill('\u0000')
            return
        }
        discardOutput()
        val token = generation
        state = state.copy(work = PasswordPdfWork.ENCRYPTING, message = null)
        operation = viewModelScope.launch {
            val output = File(directory, "encrypted-${UUID.randomUUID()}.pdf")
            var retained = false
            try {
                val result = encryptForTest?.invoke(source, output, owned)
                    ?: PasswordProtectedPdf().create(source, output, owned)
                coroutineContext.ensureActive()
                if (token != generation) return@launch
                if (result is PasswordPdfResult.Success) {
                    retained = true
                    state = state.copy(work = null, output = output, message = null)
                } else {
                    state = state.copy(work = null, message = when (result) {
                        PasswordPdfResult.InvalidPassword -> R.string.protect_password_error
                        PasswordPdfResult.InvalidSource -> R.string.protect_source_failure
                        PasswordPdfResult.StorageUnavailable -> R.string.protect_storage_failure
                        PasswordPdfResult.ResourceLimit -> R.string.protect_resource_failure
                        else -> R.string.protect_prepare_failure
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                if (token == generation) state = state.copy(work = null, message = R.string.protect_resource_failure)
            } catch (_: Exception) {
                if (token == generation) state = state.copy(work = null, message = R.string.protect_prepare_failure)
            } finally {
                owned.fill('\u0000')
                if (!retained) withContext(NonCancellable + Dispatchers.IO) { output.delete() }
            }
        }.also { job -> job.invokeOnCompletion { owned.fill('\u0000') } }
    }

    fun discardOutput() {
        if (state.busy) return
        saves.invalidate()
        generation++
        val old = state.output
        state = state.copy(output = null, message = null)
        if (old != null) viewModelScope.launch(NonCancellable + Dispatchers.IO) { old.delete() }
    }

    fun createSaveRequest(): String? {
        val output = state.output ?: return null
        if (state.busy || !state.visible) return null
        if (!output.isFile) {
            discardOutput()
            state = state.copy(message = R.string.protect_output_changed)
            return null
        }
        val request = saves.create(output, generation) ?: return null
        state = state.copy(work = PasswordPdfWork.PICKER, message = null)
        return request.id
    }

    fun save(destination: Uri?, requestId: String, canWrite: () -> Boolean) {
        val resolution = saves.consume(requestId, generation, state.output, cancelled = destination == null)
        val request = when (resolution) {
            LocalToolsSaveResolution.Ignored -> return
            is LocalToolsSaveResolution.Cancelled -> { state = state.copy(work = null); return }
            is LocalToolsSaveResolution.Invalidated -> {
                state = state.copy(work = null, message = R.string.protect_output_changed)
                return
            }
            is LocalToolsSaveResolution.Ready -> resolution.request
        }
        val target = destination ?: return
        state = state.copy(work = PasswordPdfWork.SAVING)
        operation = viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    // Never open/truncate a destination if the captured encrypted input was lost.
                    request.output.inputStream().use { input ->
                        coroutineContext.ensureActive()
                        check(canWrite())
                        val destinationStream = getApplication<Application>().contentResolver.openOutputStream(target, "wt")
                        if (destinationStream == null) false else destinationStream.use {
                            copyBounded(input, it, Long.MAX_VALUE, canWrite)
                            true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (request.generation == generation && state.output === request.output) state = state.copy(
                work = null, message = if (success) R.string.protect_saved else R.string.protect_save_failure,
            )
        }
    }

    fun beginHandoff(): PasswordPdfHandoff? {
        val output = state.output ?: return null
        if (state.busy || !state.visible) return null
        if (!output.isFile) {
            discardOutput()
            state = state.copy(message = R.string.protect_output_changed)
            return null
        }
        state = state.copy(work = PasswordPdfWork.SHARING, message = null)
        return PasswordPdfHandoff(output, generation)
    }

    fun isCurrent(handoff: PasswordPdfHandoff): Boolean =
        state.visible && state.output === handoff.output && generation == handoff.generation

    fun finishHandoff(handoff: PasswordPdfHandoff, message: Int? = null) {
        if (isCurrent(handoff)) state = state.copy(work = null, message = message)
    }

    fun showMessage(message: Int) { state = state.copy(message = message) }

    fun close() {
        saves.invalidate()
        generation++
        capture?.cancel()
        capture = null
        val finishing = operation
        operation = null
        finishing?.cancel()
        val old = workspace
        workspace = null
        state = PasswordPdfExportState()
        if (old != null) viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            finishing?.join()
            releaseDirectory(old)
        }
    }

    override fun onCleared() { close() }

    private suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long, allowed: () -> Boolean = { true }) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            check(allowed())
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit)
            output.write(buffer, 0, count)
        }
        require(total > 0)
    }

    private class CaptureStorageFailure : Exception()

    private companion object {
        const val DIRECTORY = "protected-pdf-export"
        const val MAX_BYTES = 256L * 1024 * 1024
        val active: MutableSet<File> = Collections.synchronizedSet(mutableSetOf())
        fun releaseDirectory(directory: File) {
            try { directory.deleteRecursively() } finally { active.remove(directory) }
        }
    }
}
