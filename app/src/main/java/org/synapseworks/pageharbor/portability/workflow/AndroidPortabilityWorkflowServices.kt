package org.synapseworks.pageharbor.portability.workflow

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.backup.engine.AndroidLibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationFailure
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationResult
import org.synapseworks.pageharbor.backup.engine.LibraryBackupEngine
import org.synapseworks.pageharbor.backup.engine.RoomLibraryBackupSnapshotSource
import org.synapseworks.pageharbor.backup.engine.VerifiedLibraryBackupArtifact
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.publish.AndroidSafBackupDestination
import org.synapseworks.pageharbor.backup.publish.BackupPublicationEngine
import org.synapseworks.pageharbor.backup.publish.BackupPublicationFailure
import org.synapseworks.pageharbor.backup.publish.BackupPublicationResult
import org.synapseworks.pageharbor.backup.restore.AndroidPreparedRestore
import org.synapseworks.pageharbor.backup.restore.AndroidRestoreCoordinator
import org.synapseworks.pageharbor.backup.restore.RestoreCancellationSignal
import org.synapseworks.pageharbor.backup.restore.RestoreCoordinatorFailure
import org.synapseworks.pageharbor.backup.restore.RestoreCoordinatorPrepareResult
import org.synapseworks.pageharbor.backup.restore.RestoreInputKind
import org.synapseworks.pageharbor.backup.restore.RestoreMergePolicy
import org.synapseworks.pageharbor.backup.restore.RestoreProgress
import org.synapseworks.pageharbor.backup.restore.RestoreResult
import org.synapseworks.pageharbor.backup.restore.RestoreSourceInspection
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryOperationCoordinator
import org.synapseworks.pageharbor.portability.export.AndroidRoomPortableLibraryExportCoordinator
import org.synapseworks.pageharbor.portability.export.PortableExportProgressListener
import org.synapseworks.pageharbor.reminder.backup.BackupReminderCoordinator
import org.synapseworks.pageharbor.reminder.backup.BackupReminderLibrarySnapshot
import org.synapseworks.pageharbor.reminder.backup.BackupReminderState
import org.synapseworks.pageharbor.reminder.backup.SharedPreferencesBackupReminderStateStore

internal interface PortabilityBackupArtifact : AutoCloseable {
    val summary: PortabilityBackupSummary
}

internal sealed interface PortabilityBackupCreationResult {
    data class Verified(
        val artifact: PortabilityBackupArtifact,
    ) : PortabilityBackupCreationResult

    data class Failed(
        val reason: PortabilityWorkflowFailure,
    ) : PortabilityBackupCreationResult
}

internal sealed interface PortabilityBackupPublicationResult {
    data object Verified : PortabilityBackupPublicationResult

    data class Failed(
        val reason: PortabilityWorkflowFailure,
    ) : PortabilityBackupPublicationResult
}

internal interface PortabilityBackupService {
    suspend fun create(): PortabilityBackupCreationResult

    suspend fun publish(
        artifact: PortabilityBackupArtifact,
        destination: PortabilitySafReference,
        protection: PortabilityBackupProtection,
        password: CharArray?,
    ): PortabilityBackupPublicationResult
}

internal enum class PortabilityRestoreInputKind {
    PLAIN,
    ENCRYPTED,
}

internal sealed interface PortabilityRestoreInspection {
    data class Supported(val kind: PortabilityRestoreInputKind) : PortabilityRestoreInspection
    data object Unsupported : PortabilityRestoreInspection
    data object Unavailable : PortabilityRestoreInspection
}

internal interface PortabilityPreparedRestore : AutoCloseable {
    val preview: org.synapseworks.pageharbor.backup.restore.RestorePreview
}

internal sealed interface PortabilityRestorePreparationResult {
    data class Ready(
        val prepared: PortabilityPreparedRestore,
        val existingLibraryHasContent: Boolean,
    ) : PortabilityRestorePreparationResult

    data object PasswordRequired : PortabilityRestorePreparationResult

    data class Failed(
        val reason: PortabilityWorkflowFailure,
    ) : PortabilityRestorePreparationResult
}

internal sealed interface PortabilityRestoreExecutionResult {
    data class Completed(
        val importedDocumentCount: Int,
        val skippedExactDocumentCount: Int,
    ) : PortabilityRestoreExecutionResult

    data object Cancelled : PortabilityRestoreExecutionResult

    data class Failed(
        val reason: PortabilityWorkflowFailure,
    ) : PortabilityRestoreExecutionResult
}

internal interface PortabilityRestoreService {
    /** Cleans invisible restore state and staging left by a process interruption. */
    suspend fun recoverInterruptedOperations(): Int

    suspend fun inspect(source: PortabilitySafReference): PortabilityRestoreInspection

    suspend fun prepare(
        source: PortabilitySafReference,
        password: CharArray?,
    ): PortabilityRestorePreparationResult

    suspend fun restore(
        prepared: PortabilityPreparedRestore,
        policy: RestoreMergePolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): PortabilityRestoreExecutionResult
}

internal data class PortabilityExportResult(
    val exportedDocumentCount: Int,
    val failedDocumentCount: Int,
)

internal fun interface PortabilityExportService {
    suspend fun export(
        destination: PortabilitySafReference,
        onProgress: (completedDocuments: Int, totalDocuments: Int) -> Unit,
    ): PortabilityExportResult
}

internal interface VerifiedBackupCheckpointRecorder {
    fun current(): VerifiedBackupCheckpoint?

    fun recordVerifiedBackup(summary: PortabilityBackupSummary): VerifiedBackupCheckpoint
}

internal data class PortabilityWorkflowServices(
    val backup: PortabilityBackupService,
    val restore: PortabilityRestoreService,
    val export: PortabilityExportService,
    val backupCheckpoint: VerifiedBackupCheckpointRecorder,
)

internal fun createAndroidPortabilityWorkflowServices(context: Context): PortabilityWorkflowServices {
    val applicationContext = context.applicationContext
    return PortabilityWorkflowServices(
        backup = AndroidRoomPortabilityBackupService(applicationContext),
        restore = AndroidPortabilityRestoreService(applicationContext),
        export = AndroidPortabilityExportService(applicationContext),
        backupCheckpoint = SharedPreferencesVerifiedBackupCheckpointRecorder(applicationContext),
    )
}

private class AndroidRoomPortabilityBackupService(
    context: Context,
) : PortabilityBackupService {
    private val applicationContext = context.applicationContext
    private val dao = LibraryDatabase.get(applicationContext).libraryDao()
    private val engine = LibraryBackupEngine(
        snapshotSource = RoomLibraryBackupSnapshotSource(
            dao = dao,
            fileStore = LibraryFileStore(applicationContext),
        ),
        workspace = AndroidLibraryBackupWorkspace(applicationContext),
        operationGate = LibraryOperationCoordinator.gate,
    )
    private val publicationEngine = BackupPublicationEngine()
    private val contentResolver = applicationContext.contentResolver
    private val producer = BackupProducer(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    )

    override suspend fun create(): PortabilityBackupCreationResult =
        LibraryOperationCoordinator.gate.withStableSnapshot {
            val revision = try {
                dao.metadata()?.libraryRevision ?: 0L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withStableSnapshot PortabilityBackupCreationResult.Failed(
                    PortabilityWorkflowFailure.BACKUP_SNAPSHOT_UNAVAILABLE,
                )
            }
            when (val result = engine.create(producer)) {
                is LibraryBackupCreationResult.Verified -> {
                    val manifestSummary = result.artifact.manifest.summary
                    PortabilityBackupCreationResult.Verified(
                        AndroidPortabilityBackupArtifact(
                            delegate = result.artifact,
                            summary = PortabilityBackupSummary(
                                folderCount = manifestSummary.folderCount,
                                documentCount = manifestSummary.documentCount,
                                pageCount = manifestSummary.pageCount,
                                contentBytes = manifestSummary.contentByteLength,
                                artifactBytes = result.artifact.sizeBytes,
                                libraryRevision = revision,
                            ),
                        ),
                    )
                }

                is LibraryBackupCreationResult.Failed -> PortabilityBackupCreationResult.Failed(
                    when (result.failure) {
                        LibraryBackupCreationFailure.SNAPSHOT_UNAVAILABLE ->
                            PortabilityWorkflowFailure.BACKUP_SNAPSHOT_UNAVAILABLE
                        LibraryBackupCreationFailure.INSUFFICIENT_TEMPORARY_STORAGE,
                        LibraryBackupCreationFailure.TEMPORARY_FILE_UNAVAILABLE,
                        -> PortabilityWorkflowFailure.BACKUP_TEMPORARY_STORAGE_UNAVAILABLE
                        LibraryBackupCreationFailure.ARCHIVE_WRITE_FAILED,
                        LibraryBackupCreationFailure.REOPEN_VERIFICATION_FAILED,
                        -> PortabilityWorkflowFailure.BACKUP_CREATION_FAILED
                    },
                )
            }
        }

    override suspend fun publish(
        artifact: PortabilityBackupArtifact,
        destination: PortabilitySafReference,
        protection: PortabilityBackupProtection,
        password: CharArray?,
    ): PortabilityBackupPublicationResult {
        val androidArtifact = artifact as? AndroidPortabilityBackupArtifact
            ?: return PortabilityBackupPublicationResult.Failed(
                PortabilityWorkflowFailure.BACKUP_CREATION_FAILED,
            )
        val destinationAdapter = try {
            AndroidSafBackupDestination(contentResolver, destination.value.toUri())
        } catch (_: Exception) {
            return PortabilityBackupPublicationResult.Failed(
                PortabilityWorkflowFailure.BACKUP_DESTINATION_UNAVAILABLE,
            )
        }
        val passwordCopy = password?.copyOf()
        return try {
            val result = when (protection) {
                PortabilityBackupProtection.PLAIN -> publicationEngine.publishUnencrypted(
                    androidArtifact.delegate,
                    destinationAdapter,
                )
                PortabilityBackupProtection.ENCRYPTED -> {
                    if (passwordCopy == null || passwordCopy.isEmpty()) {
                        return PortabilityBackupPublicationResult.Failed(
                            PortabilityWorkflowFailure.INVALID_PASSWORD,
                        )
                    }
                    publicationEngine.publishEncrypted(
                        androidArtifact.delegate,
                        destinationAdapter,
                        passwordCopy,
                    )
                }
            }
            result.toWorkflowResult()
        } finally {
            passwordCopy?.fill('\u0000')
        }
    }
}

private class AndroidPortabilityBackupArtifact(
    val delegate: VerifiedLibraryBackupArtifact,
    override val summary: PortabilityBackupSummary,
) : PortabilityBackupArtifact {
    override fun close() = delegate.close()
}

private fun BackupPublicationResult.toWorkflowResult(): PortabilityBackupPublicationResult = when (this) {
    is BackupPublicationResult.Verified -> PortabilityBackupPublicationResult.Verified
    is BackupPublicationResult.Failed -> PortabilityBackupPublicationResult.Failed(
        when (reason) {
            BackupPublicationFailure.DESTINATION_UNAVAILABLE ->
                PortabilityWorkflowFailure.BACKUP_DESTINATION_UNAVAILABLE
            BackupPublicationFailure.COPY_FAILED,
            BackupPublicationFailure.DESTINATION_VERIFICATION_FAILED,
            -> PortabilityWorkflowFailure.BACKUP_DESTINATION_VERIFICATION_FAILED
        },
    )
}

private class AndroidPortabilityRestoreService(
    context: Context,
) : PortabilityRestoreService {
    private val applicationContext = context.applicationContext
    private val coordinator = AndroidRestoreCoordinator(applicationContext)
    private val activeLibraryContent = RoomActiveLibraryContentSource(
        LibraryDatabase.get(applicationContext).libraryDao(),
    )

    override suspend fun recoverInterruptedOperations(): Int =
        coordinator.recoverInterruptedOperations()

    override suspend fun inspect(source: PortabilitySafReference): PortabilityRestoreInspection =
        when (val inspection = coordinator.inspect(source.value.toUri())) {
            is RestoreSourceInspection.Supported -> PortabilityRestoreInspection.Supported(
                if (inspection.kind == RestoreInputKind.ENCRYPTED) {
                    PortabilityRestoreInputKind.ENCRYPTED
                } else {
                    PortabilityRestoreInputKind.PLAIN
                },
            )
            RestoreSourceInspection.Unsupported -> PortabilityRestoreInspection.Unsupported
            RestoreSourceInspection.Unavailable -> PortabilityRestoreInspection.Unavailable
        }

    override suspend fun prepare(
        source: PortabilitySafReference,
        password: CharArray?,
    ): PortabilityRestorePreparationResult {
        val passwordCopy = password?.copyOf()
        return try {
            when (val result = coordinator.prepare(source.value.toUri(), passwordCopy)) {
                is RestoreCoordinatorPrepareResult.Ready ->
                    attachAuthoritativeLibraryContent(
                        prepared = AndroidPortabilityPreparedRestore(result.prepared),
                        source = activeLibraryContent,
                    )
                RestoreCoordinatorPrepareResult.PasswordRequired ->
                    PortabilityRestorePreparationResult.PasswordRequired
                is RestoreCoordinatorPrepareResult.Failed -> PortabilityRestorePreparationResult.Failed(
                    result.reason.toWorkflowFailure(),
                )
            }
        } finally {
            passwordCopy?.fill('\u0000')
        }
    }

    override suspend fun restore(
        prepared: PortabilityPreparedRestore,
        policy: RestoreMergePolicy,
        onProgress: (RestoreProgress) -> Unit,
    ): PortabilityRestoreExecutionResult {
        val androidPrepared = prepared as? AndroidPortabilityPreparedRestore
            ?: return PortabilityRestoreExecutionResult.Failed(
                PortabilityWorkflowFailure.RESTORE_FAILED,
            )
        val job = currentCoroutineContext()[Job]
        return when (
            val result = coordinator.restore(
                prepared = androidPrepared.delegate,
                policy = policy,
                cancellationSignal = RestoreCancellationSignal { job?.isCancelled == true },
                progressListener = { progress -> onProgress(progress) },
            )
        ) {
            is RestoreResult.Completed -> PortabilityRestoreExecutionResult.Completed(
                importedDocumentCount = result.importedDocumentCount,
                skippedExactDocumentCount = result.skippedExactDocumentCount,
            )
            is RestoreResult.Cancelled -> PortabilityRestoreExecutionResult.Cancelled
            is RestoreResult.Failed -> PortabilityRestoreExecutionResult.Failed(
                PortabilityWorkflowFailure.RESTORE_FAILED,
            )
        }
    }
}

internal fun interface ActiveLibraryContentSource {
    suspend fun hasActiveContent(): Boolean
}

private class RoomActiveLibraryContentSource(
    private val dao: LibraryDao,
) : ActiveLibraryContentSource {
    override suspend fun hasActiveContent(): Boolean =
        LibraryOperationCoordinator.gate.withStableSnapshot {
            dao.activeDocumentsPage(afterRowId = -1L, limit = 1).isNotEmpty()
        }
}

internal suspend fun attachAuthoritativeLibraryContent(
    prepared: PortabilityPreparedRestore,
    source: ActiveLibraryContentSource,
): PortabilityRestorePreparationResult = try {
    PortabilityRestorePreparationResult.Ready(
        prepared = prepared,
        existingLibraryHasContent = source.hasActiveContent(),
    )
} catch (cancelled: CancellationException) {
    prepared.close()
    throw cancelled
} catch (_: Exception) {
    prepared.close()
    PortabilityRestorePreparationResult.Failed(
        PortabilityWorkflowFailure.RESTORE_LIBRARY_UNAVAILABLE,
    )
}

private class AndroidPortabilityPreparedRestore(
    val delegate: AndroidPreparedRestore,
) : PortabilityPreparedRestore {
    override val preview = delegate.preview

    override fun close() = delegate.close()
}

private fun RestoreCoordinatorFailure.toWorkflowFailure(): PortabilityWorkflowFailure = when (this) {
    RestoreCoordinatorFailure.SOURCE_UNAVAILABLE -> PortabilityWorkflowFailure.RESTORE_SOURCE_UNAVAILABLE
    RestoreCoordinatorFailure.UNSUPPORTED_INPUT -> PortabilityWorkflowFailure.RESTORE_INPUT_UNSUPPORTED
    RestoreCoordinatorFailure.WRONG_PASSWORD_OR_DAMAGED_ENCRYPTED_BACKUP ->
        PortabilityWorkflowFailure.RESTORE_WRONG_PASSWORD_OR_DAMAGED
    RestoreCoordinatorFailure.TEMPORARY_STORAGE_UNAVAILABLE,
    RestoreCoordinatorFailure.INSUFFICIENT_STORAGE,
    RestoreCoordinatorFailure.TEMPORARY_CLEANUP_FAILED,
    -> PortabilityWorkflowFailure.RESTORE_TEMPORARY_STORAGE_UNAVAILABLE
    RestoreCoordinatorFailure.INVALID_ENCRYPTED_BACKUP,
    RestoreCoordinatorFailure.CRYPTO_UNAVAILABLE,
    RestoreCoordinatorFailure.INVALID_OR_CORRUPT_BACKUP,
    -> PortabilityWorkflowFailure.RESTORE_INVALID_OR_CORRUPT
    RestoreCoordinatorFailure.LIBRARY_UNAVAILABLE ->
        PortabilityWorkflowFailure.RESTORE_LIBRARY_UNAVAILABLE
}

private class AndroidPortabilityExportService(
    context: Context,
) : PortabilityExportService {
    private val coordinator = AndroidRoomPortableLibraryExportCoordinator(context.applicationContext)

    override suspend fun export(
        destination: PortabilitySafReference,
        onProgress: (completedDocuments: Int, totalDocuments: Int) -> Unit,
    ): PortabilityExportResult {
        val result = coordinator.export(
            treeUri = destination.value.toUri(),
            progress = PortableExportProgressListener { completed, total, _ ->
                onProgress(completed, total)
            },
        )
        return PortabilityExportResult(
            exportedDocumentCount = result.exportedDocumentCount,
            failedDocumentCount = result.failures.size,
        )
    }
}

private class SharedPreferencesVerifiedBackupCheckpointRecorder(
    context: Context,
) : VerifiedBackupCheckpointRecorder {
    private val store = SharedPreferencesBackupReminderStateStore(context.applicationContext)
    private val coordinator = BackupReminderCoordinator(store)

    override fun current(): VerifiedBackupCheckpoint? = store.read().toCheckpointOrNull()

    override fun recordVerifiedBackup(summary: PortabilityBackupSummary): VerifiedBackupCheckpoint {
        coordinator.recordVerifiedBackup(
            BackupReminderLibrarySnapshot(
                documentCount = summary.documentCount,
                pageCount = summary.pageCount,
                revision = summary.libraryRevision,
            ),
        )
        return checkNotNull(store.read().toCheckpointOrNull())
    }
}

private fun BackupReminderState.toCheckpointOrNull(): VerifiedBackupCheckpoint? =
    if (
        lastVerifiedBackupTimestampMillis > 0L &&
        libraryRevisionAtLastVerifiedBackup >= 0L
    ) {
        VerifiedBackupCheckpoint(
            timestampMillis = lastVerifiedBackupTimestampMillis,
            libraryRevision = libraryRevisionAtLastVerifiedBackup,
        )
    } else {
        null
    }
