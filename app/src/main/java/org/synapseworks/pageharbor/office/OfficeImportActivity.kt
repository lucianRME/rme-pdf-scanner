package org.synapseworks.pageharbor.office

import org.synapseworks.pageharbor.ui.theme.RmeActionRow
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.synapseworks.m35.workerprobe.OfficeAdmission
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.security.AppLockScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.synapseworks.pageharbor.ui.home.OwnedPreviewBitmap

/** Explicit local Office conversion. No document draft enters saved state/backup. */
class OfficeImportActivity : FragmentActivity() {
    internal var onSavedForTest: ((Uri) -> Unit)? = null
    private val lock: AppLockViewModel by viewModels()
    private val authentication by lazy { AndroidXAppLockAuthenticationController(this, "Unlock Office conversion") }
    private var kind by mutableStateOf("docx")
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var sourceReady by mutableStateOf(false)
    private var fonts by mutableStateOf(emptyList<String>())
    private var accepted by mutableStateOf(false)
    private var preview by mutableStateOf<OwnedPreviewBitmap?>(null)
    private var page by mutableIntStateOf(0)
    private var pageCount by mutableIntStateOf(0)
    private var checked by mutableStateOf(false)
    private var canSave by mutableStateOf(false)
    private var workspaceReady by mutableStateOf(false)
    @Volatile private var epoch = 0L
    private var operation: Job? = null
    private var source: OfficeSource? = null
    private var directory: File? = null
    private var output: OfficeOutput? = null
    private var review: OfficeReview? = null
    private var pickerKind: String? = null
    private var pendingInput: Pair<String, Uri>? = null
    private var saveTicket: OfficeOutput? = null
    private var pendingDestination: Pair<OfficeOutput, Uri?>? = null
    private var external = false
    @Volatile private var foreground = false
    @Volatile private var ownsSession = false
    private var writeInFlight = false
    private var recipeRequest: String? = null
    private val openPicker = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent = super.createIntent(context, input).apply {
            if (recipeRequest != null) putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
        }
    }) { uri ->
        external = false
        val selected = pickerKind; pickerKind = null
        if (selected != null && uri != null) { pendingInput = selected to uri; if (foreground) consumeInput() }
    }
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        external = false
        val ticket = saveTicket; saveTicket = null
        if (ticket != null) { pendingDestination = ticket to uri; if (foreground) consumeDestination() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) recipeRequest = intent.getStringExtra(OfficeRecipeOffers.REQUEST)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ownsSession = active.compareAndSet(false, true)
        setContent {
            val lockState by lock.state.collectAsState()
            LaunchedEffect(lockState.phase) { if (lockState.phase == AppLockPhase.LOCKED) clear() }
            if (lockState.phase == AppLockPhase.LOCKED) {
                AppLockScreen(lockState, authentication.availability(), { lock.authenticate(authentication) },
                    { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }, { finish() })
            } else PageHarborTheme { Content() }
        }
        // Restored picker callbacks have no in-memory ticket and cannot retarget another source.
        if (state != null) message = "The previous review ended on recreation. Open and review again; inspect any interrupted destination for a partial copy."
    }
    private fun allowed() = ownsSession && foreground && lock.state.value.phase != AppLockPhase.LOCKED && !isFinishing
    private fun guard(token: Long) { if (!allowed() || token != epoch) throw CancellationException("Office session ended") }
    override fun onResume() {
        super.onResume(); lock.onAppForegrounded(SystemClock.elapsedRealtime()); foreground = true
        if (lock.state.value.phase == AppLockPhase.LOCKED) clear() else {
            if (!workspaceReady && !busy && ownsSession && BuildConfig.M35_OFFICE_ENABLED) recoverWorkspace()
            consumeInput(); consumeDestination()
        }
    }
    override fun onStop() {
        foreground = false; lock.onAppBackgrounded(SystemClock.elapsedRealtime())
        // A reviewed picker may return after foreground authentication. Parsing,
        // conversion and rendering never continue behind another activity.
        if (!external) clear() else { operation?.cancel(); retirePreview() }
        super.onStop()
    }
    override fun onDestroy() { authentication.cancel(); clear(); if (ownsSession) active.set(false); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ActivityResultRegistry can otherwise serialize a pending provider URI
        // even though this activity has no saveable document draft. Every review
        // deliberately expires on recreation, including outstanding picker keys.
        outState.clear()
        outState.putBoolean("office_review_expired", true)
    }
    private fun clear(preserveWorkspace: Boolean = false) {
        if (!ownsSession) return
        if (!preserveWorkspace) workspaceReady = false
        if (writeInFlight) message = "Write interrupted. Inspect any partial destination before retrying; RME did not delete it."
        epoch++; operation?.cancel(); operation = null; sourceReady = false; fonts = emptyList(); accepted = false
        review = null; canSave = false; checked = false; pageCount = 0; output = null
        saveTicket = null; pendingDestination = null; pickerKind = null; pendingInput = null
        retirePreview()
        val old = source; source = null; val dir = directory; directory = null
        // The coroutine's finally block retains its local handles until native
        // cancellation/death is confirmed; cleanup must not race in-flight I/O.
        cleanupScope.launch { operationMutex.withLock {
            runCatching { old?.close() }
            // Retain a quarantined copy if a preview process can still own it.
            runCatching {
                if (dir != null) officeCoordinatorIdle(applicationContext)
                recoverOfficePreviewLeases(applicationContext); dir?.deleteRecursively()
            }
        } }
        busy = false
    }
    private fun recoverWorkspace() {
        if (busy || !allowed()) return
        val token = epoch; busy = true; message = "Checking private workspace cleanup…"
        operation = lifecycleScope.launch {
            try {
                operationMutex.withLock { withContext(Dispatchers.IO) { recoverOfficeWorkspace(this@OfficeImportActivity); OfficeRecipeOffers.recover(this@OfficeImportActivity) } }
                guard(token); workspaceReady = true; message = null
            } catch (_: CancellationException) { } catch (_: Exception) {
                if (token == epoch) { workspaceReady = false; message = "Previous Office cleanup is not confirmed. Retry cleanup before opening another source." }
            } finally { if (token == epoch) busy = false }
        }
    }
    private fun retirePreview() { val previous = preview; preview = null; previous?.release() }
    private fun clearShares() {
        if (!allowed() || busy || external) return
        val token = epoch; busy = true
        operation = lifecycleScope.launch {
            try {
                val context = currentCoroutineContext()
                operationMutex.withLock { withContext(Dispatchers.IO) { clearOfficeShares(this@OfficeImportActivity) { context.ensureActive(); guard(token) } } }
                guard(token); message = "Previous Office share grants and private copies cleared. Recipients can retain copies or already-open files."
            } catch (_: CancellationException) { } catch (_: Exception) { if (token == epoch) message = "Office share cleanup is not confirmed. Close active shares and retry." }
            finally { if (token == epoch) busy = false }
        }
    }
    private fun consumeInput() {
        val (selected, uri) = pendingInput ?: return; pendingInput = null
        if (allowed() && workspaceReady) openSource(uri, selected)
    }
    private fun openSource(uri: Uri, selected: String) {
        clear(preserveWorkspace = true); kind = selected; val token = epoch; busy = true; message = null
        operation = lifecycleScope.launch {
            var acquired: OfficeSource? = null
            try {
                val context = currentCoroutineContext()
                val declared = operationMutex.withLock {
                    runInterruptible(Dispatchers.IO) {
                        guard(token); context.ensureActive()
                        acquired = OfficeSource.open(this@OfficeImportActivity, uri, selected) { context.ensureActive(); guard(token) }
                        val values = inspectOfficeSource(this@OfficeImportActivity, checkNotNull(acquired)) { context.ensureActive(); guard(token) }
                        acquired!!.unchanged { context.ensureActive(); guard(token) }; values
                    }
                }
                guard(token); source = acquired; acquired = null; fonts = declared; sourceReady = true
            } catch (_: CancellationException) { } catch (failure: Exception) {
                if (token == epoch) message = "Source declined: ${if (failure is OfficeFailure) failure.code else OfficeAdmission.failureCode(failure)}. The original was preserved."
            } finally { acquired?.close(); if (token == epoch) busy = false }
        }
    }
    private fun convert() {
        val source = source ?: return
        if (!accepted || !allowed() || busy) return
        val token = epoch; busy = true; message = "Converting locally with the reviewed settings…"
        operation = lifecycleScope.launch {
            var created: File? = null
            try {
                operationMutex.withLock {
                    guard(token); created = withContext(Dispatchers.IO) { officeSessionDirectory(this@OfficeImportActivity) }
                    val file = File(created, "reviewed.pdf")
                    val result = convertOffice(this@OfficeImportActivity, source, file)
                    val context = currentCoroutineContext()
                    val digest = withContext(Dispatchers.IO) { officeDigest(file) { context.ensureActive(); guard(token) } }
                    guard(token)
                    val prepared = OfficeOutput(file, digest, result.pages, UUID.randomUUID().toString())
                    directory = created; created = null; output = prepared; pageCount = result.pages
                    review = OfficeReview(prepared.owner, digest, result.pages); page = 0; message = "Review every page before Save, Share or import."
                }
            } catch (_: CancellationException) { } catch (failure: Exception) {
                if (token == epoch) message = "Conversion declined: ${if (failure is OfficeFailure) failure.code else "PROCESSING_FAILED"}. The original was preserved."
            } finally {
                val unpublished = created
                if (unpublished != null) withContext(NonCancellable + Dispatchers.IO) {
                    // Cancellation timeout is not proof that a previous writer
                    // released its FD. Keep this copy quarantined if uncertain.
                    runCatching { officeCoordinatorIdle(applicationContext); unpublished.deleteRecursively() }
                }
                if (token == epoch) { busy = false; if (output != null) showPage(0) }
            }
        }
    }
    private fun showPage(index: Int) {
        val prepared = output ?: return
        if (busy || index !in 0 until prepared.pages || !allowed()) return
        val token = epoch; busy = true; canSave = false; retirePreview(); page = index; checked = false
        operation = lifecycleScope.launch {
            var rendered: Bitmap? = null
            try {
                operationMutex.withLock {
                    OfficePreviewOwnership.awaitRetirement()
                    val context = currentCoroutineContext()
                    withContext(Dispatchers.IO) { prepared.verify { context.ensureActive(); guard(token) } }
                    rendered = previewOffice(this@OfficeImportActivity, prepared.file, index, prepared.pages)
                    guard(token); if (output !== prepared) throw CancellationException()
                    preview = OfficePreviewOwnership.own(checkNotNull(rendered)); rendered = null; checked = review?.contains(index) == true
                    canSave = review?.ready(prepared.owner, prepared.digest) == true
                }
            } catch (_: CancellationException) { } catch (_: Throwable) { if (token == epoch) message = "Preview failed. Cancel this copy; no save is enabled." }
            finally { rendered?.recycle(); if (token == epoch) busy = false }
        }
    }
    private fun mark(value: Boolean) {
        val prepared = output ?: return
        if (busy || preview == null || !allowed()) return
        review?.reviewed(page, value); checked = value; canSave = review?.ready(prepared.owner, prepared.digest) == true
    }
    internal fun freezeSaveRequest(): String? {
        val prepared = output ?: return null
        if (!canSave || busy || external || !allowed()) return null
        saveTicket = prepared; external = true
        return "Office copy.pdf"
    }
    private fun beginSave() {
        val name = freezeSaveRequest() ?: return
        try { savePicker.launch(name) } catch (_: Exception) { external = false; saveTicket = null; message = "Android Save is unavailable." }
    }
    private fun consumeDestination() {
        val (ticket, uri) = pendingDestination ?: return; pendingDestination = null
        if (uri == null) { message = "Save cancelled. No destination bytes were written."; output?.let { showPage(page) }; return }
        val source = source ?: return
        if (ticket !== output || !canSave || !allowed()) { message = "The Save review expired. Open and review again."; return }
        val token = epoch; busy = true; writeInFlight = true
        operation = lifecycleScope.launch {
            try {
                val context = currentCoroutineContext()
                operationMutex.withLock { withContext(Dispatchers.IO) { saveOffice(this@OfficeImportActivity, source, ticket, uri, check = { context.ensureActive(); guard(token) }) } }
                guard(token); message = "Separate PDF saved. Original source preserved."; onSavedForTest?.invoke(uri)
            } catch (_: CancellationException) { } catch (failure: Exception) {
                if (token == epoch) message = "Save declined or interrupted (${if (failure is OfficeFailure) failure.code else "WRITE_FAILED"}). Inspect any partial destination; RME did not delete it."
            } finally { writeInFlight = false; if (token == epoch) { busy = false; showPage(page) } }
        }
    }
    // In-memory test observers, like onSavedForTest; never logs or saved state.
    internal var onShareStartedForTest: ((Intent) -> Unit)? = null
    internal var onShareFailureForTest: ((String) -> Unit)? = null
    private fun share(importCopy: Boolean) {
        val source = source ?: return; val prepared = output ?: return
        if (!canSave || busy || external || !allowed()) return
        val token = epoch; busy = true
        operation = lifecycleScope.launch {
            var file: File? = null
            try {
                val context = currentCoroutineContext()
                file = operationMutex.withLock { withContext(Dispatchers.IO) { shareOfficeCopy(this@OfficeImportActivity, source, prepared) { context.ensureActive(); guard(token) } } }
                guard(token)
                val uri = FileProvider.getUriForFile(this@OfficeImportActivity, "$packageName.fileprovider", file!!)
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri("PDF copy", uri) }
                if (importCopy) { send.setClass(this@OfficeImportActivity, MainActivity::class.java); startActivity(send) }
                else { startActivity(Intent.createChooser(send, "Share reviewed PDF copy")) }
                onShareStartedForTest?.invoke(Intent(send))
                file = null
            } catch (_: CancellationException) { onShareFailureForTest?.invoke("SHARE_CANCELLED") } catch (failure: Exception) {
                // Fixed categories only: provider/launch exceptions can contain
                // document paths or URIs and must never reach diagnostics.
                val code = when (failure) {
                    is SecurityException -> "SHARE_PERMISSION"
                    is android.content.ActivityNotFoundException -> "SHARE_NO_HANDLER"
                    is OfficeFailure -> failure.code
                    else -> "SHARE_FAILED"
                }
                onShareFailureForTest?.invoke(code.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: "SHARE_FAILED")
                if (token == epoch) message = "Sharing declined ($code). Source or reviewed copy changed, or storage is unavailable."
            }
            finally { file?.delete(); if (token == epoch) busy = false }
        }
    }
    private fun offerToRecipe() {
        val request = recipeRequest ?: return
        val source = source ?: return; val prepared = output ?: return
        if (!canSave || busy || external || !allowed()) return
        val token = epoch; busy = true
        operation = lifecycleScope.launch {
            var offer: OfficeRecipeOffer? = null
            try {
                val context = currentCoroutineContext()
                operationMutex.withLock { withContext(Dispatchers.IO) {
                    offer = OfficeRecipeOffers.create(this@OfficeImportActivity, request, source, prepared) { context.ensureActive(); guard(token) }
                } }
                guard(token)
                setResult(RESULT_OK, Intent().putExtra(OfficeRecipeOffers.REQUEST, request).putExtra(OfficeRecipeOffers.RESULT, offer!!.id))
                offer = null; finish()
            } catch (_: CancellationException) { } catch (_: Exception) {
                if (token == epoch) message = "Recipe handoff declined. Choose a primary internal-storage document with a persistable read grant, or use a separate manual Save. Nothing queued."
            } finally { offer?.close(); if (token == epoch) busy = false }
        }
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable private fun Content() {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(16.dp).verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }.testTag("office-screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Office to PDF", style = MaterialTheme.typography.headlineSmall)
                Text("Local, reviewed DOCX, XLSX and PPTX conversion. Separate PDF copy; original source is unchanged.")
                if (recipeRequest != null) Text("Recipe capture: open a document from Internal storage. Review every page, then return an immutable PDF to the saved recipe. Source access is retained only for alias checks; queued work never reconverts it. Other providers can use manual Save here.")
                val provisioned = BuildConfig.M35_OFFICE_ENABLED && android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") && ownsSession
                val enabled = provisioned && workspaceReady
                if (!provisioned) Text("The qualified ARM64 engine is unavailable in this build, or another Office session is active. No remote fallback.")
                if (provisioned && !workspaceReady) RmeTextButton(::recoverWorkspace, enabled = !busy, modifier = Modifier.testTag("office-recover")) { Text("Retry private cleanup") }
                Text("Maximum20MiB input and10 output pages. Legacy, encrypted/signed, active/external content and unsupported scripts are declined. Processing stays on this device.")
                if (!sourceReady && output == null) {
                    Row { OFFICE_MIME.keys.forEach { value -> RmeTextButton(onClick = { kind = value }, enabled = !busy && !external, modifier = Modifier.testTag("office-kind-$value")) { Text(if (kind == value) "✓ ${value.uppercase()}" else value.uppercase()) } } }
                    RmePrimaryButton(onClick = { pickerKind = kind; external = true; try { openPicker.launch(arrayOf(OFFICE_MIME.getValue(kind))) } catch (_: Exception) { external = false; pickerKind = null; message = "Android Open is unavailable." } }, enabled = enabled && !busy && !external, modifier = Modifier.testTag("office-open")) { Text("Open ${kind.uppercase()}") }
                }
                if (sourceReady && output == null) {
                    Text("Source: ${kind.uppercase()} · captured byte identity. Declared font/theme names: ${fonts.joinToString().ifEmpty { "No explicit font declarations" }}")
                    Text("Declared names are not a report of fonts actually used. Available families: Liberation Serif/Sans/Mono, Carlito and Libertine G. Missing families use reviewed substitutes; layout can differ. Latin, Greek and Cyrillic with bundled glyphs only. Unknown/RTL/complex scripts and missing glyphs are declined.")
                    Text("Explicit paper, orientation and print areas are retained; unspecified paper uses A4. Supported PPTX charts use passive caches, without recalculation. XLSX uses local admitted formulas/charts and declared print ranges; cached source values may differ from the recalculated PDF. Arbitrary chart/layout profiles are not certified.")
                    Text("Legacy, encrypted/signed, macros, actions, external resources, OLE, Word fields and embedded-workbook presentation charts are unsupported. Maximum20MiB input/10 pages/32MiB output; one60s conversion. Comments, tracked changes, interactive objects and signatures have no original editable or cryptographic guarantee.")
                    Row { Checkbox(accepted, { accepted = it }, enabled = !busy, modifier = Modifier.testTag("office-accept")); Text("I reviewed the source type, substitution, print/recalculation and flattening limits. I will compare every resulting page.") }
                    RmePrimaryButton(::convert, enabled = accepted && enabled && !busy, modifier = Modifier.testTag("office-convert")) { Text("Convert reviewed source") }
                }
                if (pageCount > 0) {
                    Text("Page ${page + 1} of $pageCount · rendered PDF copy")
                    preview?.let { Image(it.bitmap.asImageBitmap(), "Reviewed PDF page", Modifier.fillMaxWidth().heightIn(max = 700.dp).testTag("office-preview")) }
                    RmeActionRow {
                        RmeTextButton({ showPage(page - 1) }, enabled = !busy && page > 0) { Text("Previous") }
                        RmeTextButton({ showPage(page + 1) }, enabled = !busy && page + 1 < pageCount, modifier = Modifier.testTag("office-next")) { Text("Next") }
                    }
                    Row { Checkbox(checked, ::mark, enabled = !busy && preview != null, modifier = Modifier.testTag("office-reviewed")); Text("I compared this page with the original, including text, fonts, charts, print ranges, size and pagination.") }
                    Text("Covered/hidden source content may remain in originals or extractable converted text. Use Share-Safe for secure removal. This copy does not recover or edit the original Office document.")
                    RmeActionRow {
                        RmePrimaryButton(::beginSave, enabled = canSave && !busy && !external, modifier = Modifier.testTag("office-save")) { Text("Save PDF copy") }
                        RmeTextButton({ share(false) }, enabled = canSave && !busy && !external, modifier = Modifier.testTag("office-share")) { Text("Share") }
                    }
                    RmeTextButton({ share(true) }, enabled = canSave && !busy && !external, modifier = Modifier.testTag("office-import")) { Text("Import reviewed PDF into RME") }
                    if (recipeRequest != null) RmePrimaryButton(::offerToRecipe, enabled = canSave && !busy && !external, modifier = Modifier.testTag("office-recipe")) { Text("Use reviewed PDF with recipe") }
                    Text("Existing RME filters, edits, annotations and handwritten signatures are applied after importing this PDF through the existing page editor. They are not merged into an unrelated external Office document.")
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing locally…") }
                message?.let { Text(it, Modifier.testTag("office-message")) }
                RmeTextButton(::clearShares, enabled = !busy && !external, modifier = Modifier.testTag("office-clear-shares")) { Text("Clear previous Office share copies") }
                RmeTextButton({ clear(); message = "Cancelled. Originals preserved; private cleanup will finish before another source opens."; if (allowed() && BuildConfig.M35_OFFICE_ENABLED) recoverWorkspace() }, enabled = !external, modifier = Modifier.testTag("office-cancel")) { Text("Cancel / start again") }
                RmeTextButton({ finish() }) { Text("Close") }
            }
        }
    }
    private companion object {
        val active = AtomicBoolean(false)
        val operationMutex = officeProcessingMutex
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
