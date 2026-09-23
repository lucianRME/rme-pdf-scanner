package org.synapseworks.pageharbor.migration.workflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.synapseworks.pageharbor.document.importing.InboundShareResource

/** Android lifecycle owner for file, tree, and inbound-share bulk migration workflows. */
class MigrationWorkflowViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = MigrationWorkflowCoordinator(
        scope = viewModelScope,
        service = AndroidMigrationWorkflowService(application),
    )

    val state: StateFlow<MigrationWorkflowState> = coordinator.state
    val pickerRequests: Flow<MigrationPickerRequest> = coordinator.pickerRequests
    val events: Flow<MigrationWorkflowEvent> = coordinator.events

    fun selectSource(app: MigrationSourceApp): Boolean = coordinator.selectSource(app)

    fun requestMultipleFiles(): Boolean = coordinator.requestMultipleFiles()

    fun requestDocumentTree(): Boolean = coordinator.requestDocumentTree()

    fun onMultipleFilesResult(
        requestId: MigrationPickerRequestId,
        files: List<Uri>?,
        grantFlags: Int,
        declaredContentTypes: Map<Uri, String?> = emptyMap(),
    ): Boolean = coordinator.onMultipleFilesResult(
        requestId = requestId,
        files = files?.map { uri ->
            MigrationFileInput(
                source = MigrationSafReference.from(uri.toString()),
                declaredContentType = declaredContentTypes[uri],
                grantFlags = grantFlags,
            )
        },
    )

    fun onDocumentTreeResult(
        requestId: MigrationPickerRequestId,
        tree: Uri?,
        grantFlags: Int,
    ): Boolean = coordinator.onDocumentTreeResult(
        requestId = requestId,
        tree = tree?.let { uri -> MigrationSafReference.from(uri.toString()) },
        grantFlags = grantFlags,
    )

    fun startInboundShare(
        resources: List<InboundShareResource>,
        sourceApp: MigrationSourceApp = MigrationSourceApp.OTHER,
    ): Boolean = coordinator.startInboundShare(
        resources = resources.map { resource ->
            MigrationShareInput(
                source = MigrationSafReference.from(resource.uri.toString()),
                declaredContentType = resource.declaredContentType,
                grantFlags = resource.grantFlags,
            )
        },
        sourceApp = sourceApp,
    )

    fun retryPreparation(): Boolean = coordinator.retryPreparation()

    fun setImportAnyway(
        selectionId: MigrationDuplicateSelectionId,
        importAnyway: Boolean,
    ): Boolean = coordinator.setImportAnyway(selectionId, importAnyway)

    fun importDocuments(): Boolean = coordinator.importDocuments()

    fun retryFailures(): Boolean = coordinator.retryFailures()

    fun retryIncomplete(): Boolean = coordinator.retryIncomplete()

    fun cancelCurrentOperation(): Boolean = coordinator.cancelCurrentOperation()

    fun dismissResult() = coordinator.dismissResult()

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }
}
