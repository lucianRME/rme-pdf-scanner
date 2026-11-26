package org.synapseworks.pageharbor.security

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import org.synapseworks.pageharbor.migration.workflow.MigrationPickerRequest
import org.synapseworks.pageharbor.portability.workflow.PortabilityPickerRequest

/**
 * Configuration-retained queue for payloads which arrive while app lock is active. The queue is
 * deliberately memory-only: provider references are not written to preferences, logs, or disk.
 * Process death safely abandons an Activity result; launch intents are offered again by the
 * recreated Activity and all library publishers remain atomic.
 */
class ProtectedActionQueueViewModel : ViewModel() {
    private val pending = ArrayDeque<PendingProtectedAction>()
    private val inboundFingerprints = mutableSetOf<String>()
    private var launchedPortabilityPicker: PortabilityPickerRequest? = null
    private var launchedMigrationPicker: MigrationPickerRequest? = null
    private var launchedPasswordPdfSaveRequest: String? = null
    private var launchedLocalToolsSaveRequest: String? = null

    @Synchronized
    internal fun enqueue(action: PendingProtectedAction) {
        if (action is PendingProtectedAction.InboundIntent) {
            if (!inboundFingerprints.add(action.fingerprint)) return
        }
        pending.addLast(action)
    }

    @Synchronized
    internal fun poll(): PendingProtectedAction? {
        val action = pending.removeFirstOrNull() ?: return null
        if (action is PendingProtectedAction.InboundIntent) {
            inboundFingerprints.remove(action.fingerprint)
        }
        return action
    }

    @Synchronized
    internal fun markPortabilityPickerLaunched(request: PortabilityPickerRequest) {
        launchedPortabilityPicker = request
    }

    @Synchronized
    internal fun consumePortabilityPickerRequest(): PortabilityPickerRequest? =
        launchedPortabilityPicker.also { launchedPortabilityPicker = null }

    @Synchronized
    internal fun markMigrationPickerLaunched(request: MigrationPickerRequest) {
        launchedMigrationPicker = request
    }

    @Synchronized
    internal fun consumeMigrationPickerRequest(): MigrationPickerRequest? =
        launchedMigrationPicker.also { launchedMigrationPicker = null }

    @Synchronized
    internal fun markLocalToolsSavePickerLaunched(requestId: String): Boolean {
        if (launchedLocalToolsSaveRequest != null) return false
        launchedLocalToolsSaveRequest = requestId
        return true
    }

    @Synchronized
    internal fun consumeLocalToolsSavePickerRequest(): String? =
        launchedLocalToolsSaveRequest.also { launchedLocalToolsSaveRequest = null }

    @Synchronized
    internal fun localToolsSavePickerRequestForRecreation(): String? = launchedLocalToolsSaveRequest

    @Synchronized
    internal fun restoreLocalToolsSavePickerRequest(requestId: String?) {
        // Only this opaque correlation token is restored; no document, URI, password or handwriting.
        if (launchedLocalToolsSaveRequest == null && requestId != null) {
            launchedLocalToolsSaveRequest = requestId
        }
    }
    @Synchronized
    internal fun markPasswordPdfSavePickerLaunched(requestId: String): Boolean {
        if (launchedPasswordPdfSaveRequest != null) return false
        launchedPasswordPdfSaveRequest = requestId
        return true
    }

    @Synchronized
    internal fun consumePasswordPdfSavePickerRequest(): String? =
        launchedPasswordPdfSaveRequest.also { launchedPasswordPdfSaveRequest = null }

    @Synchronized
    internal fun passwordPdfSavePickerRequestForRecreation(): String? = launchedPasswordPdfSaveRequest

    @Synchronized
    internal fun restorePasswordPdfSavePickerRequest(requestId: String?) {
        // Opaque correlation only; no document, URI or password is restored.
        if (launchedPasswordPdfSaveRequest == null && requestId != null) {
            launchedPasswordPdfSaveRequest = requestId
        }
    }
}

internal sealed interface PendingProtectedAction {
    data class ScannerResult(val resultCode: Int, val data: Intent?) : PendingProtectedAction

    data class ImportDocuments(val uris: List<Uri>) : PendingProtectedAction

    data class PortabilityPickerResult(
        val request: PortabilityPickerRequest,
        val uri: Uri?,
    ) : PendingProtectedAction

    data class MigrationPickerResult(
        val request: MigrationPickerRequest,
        val resultCode: Int,
        val data: Intent?,
    ) : PendingProtectedAction

    data class NormalPdfDestination(val uri: Uri?) : PendingProtectedAction

    data class PageDestination(val uri: Uri?) : PendingProtectedAction

    data class SearchablePdfDestination(val uri: Uri?) : PendingProtectedAction

    data class TargetSizePdfDestination(val uri: Uri?) : PendingProtectedAction

    data class CardCopyScannerResult(val resultCode: Int, val data: Intent?) : PendingProtectedAction

    data class CardCopyImageResult(val uri: Uri?) : PendingProtectedAction

    data class CardCopyPdfDestination(val uri: Uri?) : PendingProtectedAction

    data class ShareSafePdfDestination(val uri: Uri?) : PendingProtectedAction

    data class PasswordPdfDestination(val uri: Uri?, val requestId: String) : PendingProtectedAction

    data class LocalToolsPdfDestination(val uri: Uri?, val requestId: String) : PendingProtectedAction

    data class LaunchPortabilityPicker(
        val request: PortabilityPickerRequest,
    ) : PendingProtectedAction

    data class LaunchMigrationPicker(
        val request: MigrationPickerRequest,
    ) : PendingProtectedAction

    data class InboundIntent(
        val intent: Intent,
        val fingerprint: String,
    ) : PendingProtectedAction
}
