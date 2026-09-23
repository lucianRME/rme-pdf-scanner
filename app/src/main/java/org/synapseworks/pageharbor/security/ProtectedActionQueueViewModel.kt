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
