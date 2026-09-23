package org.synapseworks.pageharbor.portability.workflow

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.backup.restore.RestoreProgress
import org.synapseworks.pageharbor.review.ReviewMilestone

/**
 * Configuration-safe owner for portability operations. It retains only verified backup artifacts,
 * verified restore staging, opaque picker references, and operation-local password copies.
 */
class PortabilityWorkflowCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val services: PortabilityWorkflowServices,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val operationContext: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {
    private val lock = Any()
    private val requestIds = AtomicLong(0L)
    private val pickerChannel = Channel<PortabilityPickerRequest>(Channel.UNLIMITED)
    private val eventChannel = Channel<PortabilityWorkflowEvent>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(
        PortabilityWorkflowState(
            lastVerifiedBackup = runCatching(services.backupCheckpoint::current).getOrNull(),
        ),
    )

    val state: StateFlow<PortabilityWorkflowState> = mutableState.asStateFlow()
    val pickerRequests: Flow<PortabilityPickerRequest> = pickerChannel.receiveAsFlow()
    val events: Flow<PortabilityWorkflowEvent> = eventChannel.receiveAsFlow()

    private var activeJob: Job? = null
    private var pendingBackup: PendingBackup? = null
    private var pendingRestoreSource: PortabilitySafReference? = null
    private var preparedRestore: PortabilityPreparedRestore? = null
    private var closed = false

    /** Copies [password] synchronously. The caller remains responsible for wiping its own array. */
    fun createBackup(
        protection: PortabilityBackupProtection,
        password: CharArray? = null,
    ): Boolean {
        val passwordCopy = when (protection) {
            PortabilityBackupProtection.PLAIN -> null
            PortabilityBackupProtection.ENCRYPTED -> {
                if (password == null || password.isEmpty()) {
                    return replaceWithFailure(
                        PortabilityWorkflowKind.BACKUP,
                        PortabilityWorkflowFailure.INVALID_PASSWORD,
                    )
                }
                password.copyOf()
            }
        }
        var transferred = false
        val launched = launchOperation(
            workflow = PortabilityWorkflowKind.BACKUP,
            initialStatus = PortabilityOperationStatus.CreatingBackup(protection),
            unexpectedFailure = PortabilityWorkflowFailure.BACKUP_CREATION_FAILED,
        ) {
            var artifact: PortabilityBackupArtifact? = null
            try {
                when (val result = services.backup.create()) {
                    is PortabilityBackupCreationResult.Failed -> publishFailure(
                        PortabilityWorkflowKind.BACKUP,
                        result.reason,
                    )
                    is PortabilityBackupCreationResult.Verified -> {
                        artifact = result.artifact
                        val requestId = nextRequestId()
                        val pending = PendingBackup(
                            requestId = requestId,
                            artifact = result.artifact,
                            protection = protection,
                            password = passwordCopy,
                        )
                        val accepted = synchronized(lock) {
                            if (closed) {
                                false
                            } else {
                                pendingBackup = pending
                                mutableState.value = mutableState.value.copy(
                                    operation = PortabilityOperationStatus.AwaitingBackupDestination(
                                        requestId = requestId,
                                        protection = protection,
                                        summary = result.artifact.summary,
                                    ),
                                )
                                true
                            }
                        }
                        if (accepted) {
                            transferred = true
                            artifact = null
                            val request = PortabilityPickerRequest.CreateBackupDocument(
                                requestId = requestId,
                                suggestedFileName = if (protection == PortabilityBackupProtection.ENCRYPTED) {
                                    ENCRYPTED_BACKUP_FILE_NAME
                                } else {
                                    PLAIN_BACKUP_FILE_NAME
                                },
                                mimeType = if (protection == PortabilityBackupProtection.ENCRYPTED) {
                                    BINARY_MIME_TYPE
                                } else {
                                    ZIP_MIME_TYPE
                                },
                                protection = protection,
                            )
                            if (pickerChannel.trySend(request).isFailure) {
                                synchronized(lock) {
                                    if (pendingBackup === pending) {
                                        pendingBackup = null
                                        pending.release()
                                        mutableState.value = mutableState.value.copy(
                                            operation = PortabilityOperationStatus.Failed(
                                                PortabilityWorkflowKind.BACKUP,
                                                PortabilityWorkflowFailure.PICKER_REQUEST_UNAVAILABLE,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                artifact?.close()
                if (!transferred) passwordCopy?.fill('\u0000')
            }
        }
        if (!launched) passwordCopy?.fill('\u0000')
        return launched
    }

    fun onBackupDestinationResult(
        requestId: PortabilityPickerRequestId,
        destinationReference: String?,
    ): Boolean {
        val pending = synchronized(lock) {
            pendingBackup?.takeIf { it.requestId == requestId }?.also { pendingBackup = null }
        } ?: return false
        if (destinationReference == null) {
            pending.release()
            publishStatus(PortabilityOperationStatus.Cancelled(PortabilityWorkflowKind.BACKUP))
            return true
        }
        val launched = launchOperation(
            workflow = PortabilityWorkflowKind.BACKUP,
            initialStatus = PortabilityOperationStatus.PublishingBackup(
                protection = pending.protection,
                summary = pending.artifact.summary,
            ),
            unexpectedFailure = PortabilityWorkflowFailure.BACKUP_DESTINATION_VERIFICATION_FAILED,
            replaceOwnedState = false,
        ) {
            try {
                when (
                    val result = services.backup.publish(
                        artifact = pending.artifact,
                        destination = PortabilitySafReference(destinationReference),
                        protection = pending.protection,
                        password = pending.password,
                    )
                ) {
                    is PortabilityBackupPublicationResult.Failed -> publishFailure(
                        PortabilityWorkflowKind.BACKUP,
                        result.reason,
                    )
                    PortabilityBackupPublicationResult.Verified -> {
                        val checkpoint = runCatching {
                            services.backupCheckpoint.recordVerifiedBackup(pending.artifact.summary)
                        }.getOrElse {
                            VerifiedBackupCheckpoint(
                                timestampMillis = nowMillis().coerceAtLeast(1L),
                                libraryRevision = pending.artifact.summary.libraryRevision,
                            )
                        }
                        synchronized(lock) {
                            if (!closed) {
                                mutableState.value = PortabilityWorkflowState(
                                    operation = PortabilityOperationStatus.BackupVerified(
                                        protection = pending.protection,
                                        summary = pending.artifact.summary,
                                    ),
                                    lastVerifiedBackup = checkpoint,
                                )
                                emitVerifiedMilestone(ReviewMilestone.BACKUP_VERIFIED)
                            }
                        }
                    }
                }
            } finally {
                pending.release()
            }
        }
        if (!launched) pending.release()
        return launched
    }

    fun requestRestoreSource(): Boolean = requestPicker(
        workflow = PortabilityWorkflowKind.RESTORE,
        statusFactory = PortabilityOperationStatus::AwaitingRestoreSource,
        requestFactory = PortabilityPickerRequest::OpenRestoreDocument,
    )

    fun onRestoreSourceResult(
        requestId: PortabilityPickerRequestId,
        sourceReference: String?,
    ): Boolean {
        val expected = synchronized(lock) {
            (mutableState.value.operation as? PortabilityOperationStatus.AwaitingRestoreSource)
                ?.requestId == requestId
        }
        if (!expected) return false
        if (sourceReference == null) {
            publishStatus(PortabilityOperationStatus.Cancelled(PortabilityWorkflowKind.RESTORE))
            return true
        }
        val source = PortabilitySafReference(sourceReference)
        return launchOperation(
            workflow = PortabilityWorkflowKind.RESTORE,
            initialStatus = PortabilityOperationStatus.InspectingRestore,
            unexpectedFailure = PortabilityWorkflowFailure.RESTORE_SOURCE_UNAVAILABLE,
            replaceOwnedState = false,
        ) {
            services.restore.recoverInterruptedOperations()
            when (val inspection = services.restore.inspect(source)) {
                PortabilityRestoreInspection.Unavailable -> publishFailure(
                    PortabilityWorkflowKind.RESTORE,
                    PortabilityWorkflowFailure.RESTORE_SOURCE_UNAVAILABLE,
                )
                PortabilityRestoreInspection.Unsupported -> publishFailure(
                    PortabilityWorkflowKind.RESTORE,
                    PortabilityWorkflowFailure.RESTORE_INPUT_UNSUPPORTED,
                )
                is PortabilityRestoreInspection.Supported -> {
                    if (inspection.kind == PortabilityRestoreInputKind.ENCRYPTED) {
                        synchronized(lock) {
                            if (!closed) {
                                pendingRestoreSource = source
                                mutableState.value = mutableState.value.copy(
                                    operation = PortabilityOperationStatus.AwaitingRestorePassword(),
                                )
                            }
                        }
                    } else {
                        publishStatus(PortabilityOperationStatus.PreparingRestore)
                        acceptRestorePreparation(
                            source = source,
                            result = services.restore.prepare(source, password = null),
                            retainSourceForPassword = false,
                        )
                    }
                }
            }
        }
    }

    /** Copies [password] synchronously. The caller remains responsible for wiping its own array. */
    fun submitRestorePassword(password: CharArray): Boolean {
        if (password.isEmpty()) {
            val validState = synchronized(lock) {
                pendingRestoreSource != null &&
                    mutableState.value.operation is PortabilityOperationStatus.AwaitingRestorePassword
            }
            if (validState) {
                publishStatus(
                    PortabilityOperationStatus.AwaitingRestorePassword(
                        PortabilityWorkflowFailure.INVALID_PASSWORD,
                    ),
                )
            }
            return validState
        }
        val source = synchronized(lock) {
            pendingRestoreSource?.takeIf {
                mutableState.value.operation is PortabilityOperationStatus.AwaitingRestorePassword
            }
        } ?: return false
        val passwordCopy = password.copyOf()
        val launched = launchOperation(
            workflow = PortabilityWorkflowKind.RESTORE,
            initialStatus = PortabilityOperationStatus.PreparingRestore,
            unexpectedFailure = PortabilityWorkflowFailure.RESTORE_INVALID_OR_CORRUPT,
            replaceOwnedState = false,
        ) {
            var retainSource = false
            try {
                val result = services.restore.prepare(source, passwordCopy)
                retainSource = result == PortabilityRestorePreparationResult.PasswordRequired ||
                    (result is PortabilityRestorePreparationResult.Failed &&
                        result.reason == PortabilityWorkflowFailure.RESTORE_WRONG_PASSWORD_OR_DAMAGED)
                acceptRestorePreparation(source, result, retainSource)
            } finally {
                passwordCopy.fill('\u0000')
                if (!retainSource) {
                    synchronized(lock) {
                        if (pendingRestoreSource == source) pendingRestoreSource = null
                    }
                }
            }
        }
        if (!launched) passwordCopy.fill('\u0000')
        return launched
    }

    fun restore(policy: RestoreMergePolicy): Boolean {
        val prepared = synchronized(lock) {
            preparedRestore?.takeIf {
                mutableState.value.operation is PortabilityOperationStatus.RestoreReady
            }?.also { preparedRestore = null }
        } ?: return false
        val preview = prepared.preview
        val launched = launchOperation(
            workflow = PortabilityWorkflowKind.RESTORE,
            initialStatus = PortabilityOperationStatus.Restoring(
                preview = preview,
                mergePolicy = policy,
                completedDocuments = 0,
                totalDocuments = preview.documentCount,
            ),
            unexpectedFailure = PortabilityWorkflowFailure.RESTORE_FAILED,
            replaceOwnedState = false,
        ) {
            try {
                when (
                    val result = services.restore.restore(
                        prepared = prepared,
                        policy = policy,
                        onProgress = { progress -> publishRestoreProgress(preview, policy, progress) },
                    )
                ) {
                    is PortabilityRestoreExecutionResult.Completed -> {
                        publishStatus(
                            PortabilityOperationStatus.RestoreCompleted(
                                importedDocumentCount = result.importedDocumentCount,
                                skippedExactDocumentCount = result.skippedExactDocumentCount,
                            ),
                        )
                        if (result.importedDocumentCount > 0) {
                            emitVerifiedMilestone(ReviewMilestone.RESTORE_COMPLETED)
                        }
                    }
                    PortabilityRestoreExecutionResult.Cancelled -> publishStatus(
                        PortabilityOperationStatus.Cancelled(PortabilityWorkflowKind.RESTORE),
                    )
                    is PortabilityRestoreExecutionResult.Failed -> publishFailure(
                        PortabilityWorkflowKind.RESTORE,
                        result.reason,
                    )
                }
            } finally {
                prepared.close()
            }
        }
        if (!launched) prepared.close()
        return launched
    }

    fun requestWholeLibraryExport(): Boolean = requestPicker(
        workflow = PortabilityWorkflowKind.WHOLE_LIBRARY_EXPORT,
        statusFactory = PortabilityOperationStatus::AwaitingExportTree,
        requestFactory = PortabilityPickerRequest::OpenWholeLibraryExportTree,
    )

    fun onWholeLibraryExportTreeResult(
        requestId: PortabilityPickerRequestId,
        treeReference: String?,
    ): Boolean {
        val expected = synchronized(lock) {
            (mutableState.value.operation as? PortabilityOperationStatus.AwaitingExportTree)
                ?.requestId == requestId
        }
        if (!expected) return false
        if (treeReference == null) {
            publishStatus(
                PortabilityOperationStatus.Cancelled(PortabilityWorkflowKind.WHOLE_LIBRARY_EXPORT),
            )
            return true
        }
        return launchOperation(
            workflow = PortabilityWorkflowKind.WHOLE_LIBRARY_EXPORT,
            initialStatus = PortabilityOperationStatus.Exporting(0, 0),
            unexpectedFailure = PortabilityWorkflowFailure.EXPORT_FAILED,
            replaceOwnedState = false,
        ) {
            val result = services.export.export(
                PortabilitySafReference(treeReference),
            ) { completed, total ->
                publishStatus(
                    PortabilityOperationStatus.Exporting(
                        completedDocuments = completed.coerceAtLeast(0),
                        totalDocuments = total.coerceAtLeast(0),
                    ),
                )
            }
            publishStatus(
                PortabilityOperationStatus.ExportCompleted(
                    exportedDocumentCount = result.exportedDocumentCount,
                    failedDocumentCount = result.failedDocumentCount,
                ),
            )
        }
    }

    fun cancelCurrentOperation(): Boolean {
        val job: Job?
        val workflow: PortabilityWorkflowKind
        synchronized(lock) {
            if (closed) return false
            workflow = mutableState.value.operation.workflowKindOrNull() ?: return false
            job = activeJob
            if (job == null) {
                releaseOwnedStateLocked()
                drainPickerRequestsLocked()
                mutableState.value = mutableState.value.copy(
                    operation = PortabilityOperationStatus.Cancelled(workflow),
                )
                return true
            }
        }
        job?.cancel()
        return true
    }

    /** Releases a retained preview without reporting an operation cancellation. */
    fun dismissResult() {
        synchronized(lock) {
            if (closed || activeJob != null) return
            releaseOwnedStateLocked()
            drainPickerRequestsLocked()
            mutableState.value = mutableState.value.copy(operation = PortabilityOperationStatus.Idle)
        }
    }

    override fun close() {
        val job: Job?
        synchronized(lock) {
            if (closed) return
            closed = true
            job = activeJob
            releaseOwnedStateLocked()
            drainPickerRequestsLocked()
            pickerChannel.close()
            eventChannel.close()
        }
        job?.cancel()
    }

    private fun requestPicker(
        workflow: PortabilityWorkflowKind,
        statusFactory: (PortabilityPickerRequestId) -> PortabilityOperationStatus,
        requestFactory: (PortabilityPickerRequestId) -> PortabilityPickerRequest,
    ): Boolean {
        val request: PortabilityPickerRequest
        synchronized(lock) {
            if (closed || activeJob != null) return false
            releaseOwnedStateLocked()
            drainPickerRequestsLocked()
            val requestId = nextRequestId()
            request = requestFactory(requestId)
            mutableState.value = mutableState.value.copy(operation = statusFactory(requestId))
        }
        if (pickerChannel.trySend(request).isSuccess) return true
        publishFailure(workflow, PortabilityWorkflowFailure.PICKER_REQUEST_UNAVAILABLE)
        return false
    }

    private fun acceptRestorePreparation(
        source: PortabilitySafReference,
        result: PortabilityRestorePreparationResult,
        retainSourceForPassword: Boolean,
    ) {
        when (result) {
            is PortabilityRestorePreparationResult.Ready -> {
                val accepted = synchronized(lock) {
                    if (closed) {
                        false
                    } else {
                        pendingRestoreSource = null
                        preparedRestore?.close()
                        preparedRestore = result.prepared
                        mutableState.value = mutableState.value.copy(
                            operation = PortabilityOperationStatus.RestoreReady(
                                preview = result.prepared.preview,
                                existingLibraryHasContent = result.existingLibraryHasContent,
                            ),
                        )
                        true
                    }
                }
                if (!accepted) result.prepared.close()
            }
            PortabilityRestorePreparationResult.PasswordRequired -> synchronized(lock) {
                if (!closed) {
                    pendingRestoreSource = source
                    mutableState.value = mutableState.value.copy(
                        operation = PortabilityOperationStatus.AwaitingRestorePassword(
                            PortabilityWorkflowFailure.INVALID_PASSWORD,
                        ),
                    )
                }
            }
            is PortabilityRestorePreparationResult.Failed -> {
                if (retainSourceForPassword) {
                    synchronized(lock) {
                        if (!closed) {
                            pendingRestoreSource = source
                            mutableState.value = mutableState.value.copy(
                                operation = PortabilityOperationStatus.AwaitingRestorePassword(
                                    result.reason,
                                ),
                            )
                        }
                    }
                } else {
                    synchronized(lock) {
                        if (pendingRestoreSource == source) pendingRestoreSource = null
                    }
                    publishFailure(PortabilityWorkflowKind.RESTORE, result.reason)
                }
            }
        }
    }

    private fun publishRestoreProgress(
        preview: org.synapseworks.pageharbor.backup.restore.RestorePreview,
        policy: RestoreMergePolicy,
        progress: RestoreProgress,
    ) {
        publishStatus(
            PortabilityOperationStatus.Restoring(
                preview = preview,
                mergePolicy = policy,
                completedDocuments = progress.preparedDocumentCount.coerceAtLeast(0),
                totalDocuments = progress.totalDocumentCount.coerceAtLeast(0),
            ),
        )
    }

    private fun launchOperation(
        workflow: PortabilityWorkflowKind,
        initialStatus: PortabilityOperationStatus,
        unexpectedFailure: PortabilityWorkflowFailure,
        replaceOwnedState: Boolean = true,
        block: suspend () -> Unit,
    ): Boolean {
        val operationToken = Any()
        val job: Job
        synchronized(lock) {
            if (closed || activeJob != null) return false
            if (replaceOwnedState) releaseOwnedStateLocked()
            drainPickerRequestsLocked()
            mutableState.value = mutableState.value.copy(operation = initialStatus)
            job = scope.launch(context = operationContext, start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    publishStatus(PortabilityOperationStatus.Cancelled(workflow))
                } catch (_: Exception) {
                    publishFailure(workflow, unexpectedFailure)
                } finally {
                    synchronized(lock) {
                        if (activeJobToken === operationToken) {
                            activeJob = null
                            activeJobToken = null
                        }
                    }
                }
            }
            activeJob = job
            activeJobToken = operationToken
        }
        job.start()
        return true
    }

    private var activeJobToken: Any? = null

    private fun replaceWithFailure(
        workflow: PortabilityWorkflowKind,
        failure: PortabilityWorkflowFailure,
    ): Boolean = synchronized(lock) {
        if (closed || activeJob != null) return@synchronized false
        releaseOwnedStateLocked()
        drainPickerRequestsLocked()
        mutableState.value = mutableState.value.copy(
            operation = PortabilityOperationStatus.Failed(workflow, failure),
        )
        true
    }

    private fun publishFailure(
        workflow: PortabilityWorkflowKind,
        failure: PortabilityWorkflowFailure,
    ) {
        publishStatus(PortabilityOperationStatus.Failed(workflow, failure))
    }

    private fun publishStatus(status: PortabilityOperationStatus) {
        synchronized(lock) {
            if (!closed) mutableState.value = mutableState.value.copy(operation = status)
        }
    }

    private fun emitVerifiedMilestone(milestone: ReviewMilestone) {
        if (!closed) {
            eventChannel.trySend(PortabilityWorkflowEvent.VerifiedReviewMilestone(milestone))
        }
    }

    private fun releaseOwnedStateLocked() {
        pendingBackup?.release()
        pendingBackup = null
        pendingRestoreSource = null
        preparedRestore?.close()
        preparedRestore = null
    }

    private fun drainPickerRequestsLocked() {
        while (pickerChannel.tryReceive().isSuccess) {
            // Request IDs also reject results from a picker already launched by the previous owner.
        }
    }

    private fun nextRequestId(): PortabilityPickerRequestId {
        val next = requestIds.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        return PortabilityPickerRequestId(next)
    }

    private class PendingBackup(
        val requestId: PortabilityPickerRequestId,
        val artifact: PortabilityBackupArtifact,
        val protection: PortabilityBackupProtection,
        val password: CharArray?,
    ) {
        fun release() {
            password?.fill('\u0000')
            artifact.close()
        }

        override fun toString(): String =
            "PendingBackup(requestId=$requestId, protection=$protection, password=<redacted>)"
    }

    private fun PortabilityOperationStatus.workflowKindOrNull(): PortabilityWorkflowKind? = when (this) {
        is PortabilityOperationStatus.CreatingBackup,
        is PortabilityOperationStatus.AwaitingBackupDestination,
        is PortabilityOperationStatus.PublishingBackup,
        is PortabilityOperationStatus.BackupVerified,
        -> PortabilityWorkflowKind.BACKUP
        is PortabilityOperationStatus.AwaitingRestoreSource,
        PortabilityOperationStatus.InspectingRestore,
        is PortabilityOperationStatus.AwaitingRestorePassword,
        PortabilityOperationStatus.PreparingRestore,
        is PortabilityOperationStatus.RestoreReady,
        is PortabilityOperationStatus.Restoring,
        is PortabilityOperationStatus.RestoreCompleted,
        -> PortabilityWorkflowKind.RESTORE
        is PortabilityOperationStatus.AwaitingExportTree,
        is PortabilityOperationStatus.Exporting,
        is PortabilityOperationStatus.ExportCompleted,
        -> PortabilityWorkflowKind.WHOLE_LIBRARY_EXPORT
        PortabilityOperationStatus.Idle,
        is PortabilityOperationStatus.Cancelled,
        is PortabilityOperationStatus.Failed,
        -> null
    }

    private companion object {
        const val PLAIN_BACKUP_FILE_NAME = "RME-library-backup.zip"
        const val ENCRYPTED_BACKUP_FILE_NAME = "RME-library-backup.rmeenc"
        const val ZIP_MIME_TYPE = "application/zip"
        const val BINARY_MIME_TYPE = "application/octet-stream"
    }
}
