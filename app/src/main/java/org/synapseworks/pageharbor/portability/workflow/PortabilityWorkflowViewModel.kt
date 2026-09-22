package org.synapseworks.pageharbor.portability.workflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy

/** Android lifecycle owner for backup, restore, and whole-library SAF export orchestration. */
class PortabilityWorkflowViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = PortabilityWorkflowCoordinator(
        scope = viewModelScope,
        services = createAndroidPortabilityWorkflowServices(application),
    )

    val state: StateFlow<PortabilityWorkflowState> = coordinator.state
    val pickerRequests: Flow<PortabilityPickerRequest> = coordinator.pickerRequests
    val events: Flow<PortabilityWorkflowEvent> = coordinator.events

    fun createBackup(
        protection: PortabilityBackupProtection,
        password: CharArray? = null,
    ): Boolean = coordinator.createBackup(protection, password)

    fun onBackupDestinationResult(
        requestId: PortabilityPickerRequestId,
        destination: Uri?,
    ): Boolean = coordinator.onBackupDestinationResult(requestId, destination?.toString())

    fun requestRestoreSource(): Boolean = coordinator.requestRestoreSource()

    fun onRestoreSourceResult(
        requestId: PortabilityPickerRequestId,
        source: Uri?,
    ): Boolean = coordinator.onRestoreSourceResult(requestId, source?.toString())

    fun submitRestorePassword(password: CharArray): Boolean =
        coordinator.submitRestorePassword(password)

    fun restore(policy: RestoreMergePolicy): Boolean = coordinator.restore(policy)

    fun requestWholeLibraryExport(): Boolean = coordinator.requestWholeLibraryExport()

    fun onWholeLibraryExportTreeResult(
        requestId: PortabilityPickerRequestId,
        tree: Uri?,
    ): Boolean = coordinator.onWholeLibraryExportTreeResult(requestId, tree?.toString())

    fun cancelCurrentOperation(): Boolean = coordinator.cancelCurrentOperation()

    fun dismissResult() = coordinator.dismissResult()

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }
}
