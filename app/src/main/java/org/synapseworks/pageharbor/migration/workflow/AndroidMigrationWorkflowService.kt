package org.synapseworks.pageharbor.migration.workflow

import android.content.Context
import android.net.Uri
import org.synapseworks.pageharbor.document.importing.InboundShareResource
import org.synapseworks.pageharbor.migration.AndroidBulkMigrationCoordinator
import org.synapseworks.pageharbor.migration.MigrationBatchProgressListener
import org.synapseworks.pageharbor.migration.MigrationCancellationSignal
import org.synapseworks.pageharbor.migration.MigrationDuplicateDecisions
import org.synapseworks.pageharbor.migration.MigrationExecutionResult
import org.synapseworks.pageharbor.migration.MigrationFileResource
import org.synapseworks.pageharbor.migration.MigrationPreviewProgressListener
import org.synapseworks.pageharbor.migration.MigrationTreeDiscoveryProgressListener
import org.synapseworks.pageharbor.migration.PreparedMigrationBatch
import org.synapseworks.pageharbor.migration.MigrationBatchReport

/** An operation-local SAF reference whose diagnostic rendering cannot reveal the provider URI. */
internal class MigrationSafReference private constructor(internal val value: String) {
    init {
        require(value.isNotBlank())
    }

    override fun toString(): String = "MigrationSafReference(REDACTED)"

    companion object {
        fun from(value: String): MigrationSafReference = MigrationSafReference(value)
    }
}

internal data class MigrationFileInput(
    val source: MigrationSafReference,
    val declaredContentType: String? = null,
    val grantFlags: Int = 0,
)

internal data class MigrationShareInput(
    val source: MigrationSafReference,
    val declaredContentType: String? = null,
    val grantFlags: Int = 0,
)

internal sealed interface MigrationPreparationInput {
    val selection: MigrationSourceSelection

    data class Files(
        override val selection: MigrationSourceSelection,
        val files: List<MigrationFileInput>,
    ) : MigrationPreparationInput

    data class Tree(
        override val selection: MigrationSourceSelection,
        val tree: MigrationSafReference,
        val grantFlags: Int,
    ) : MigrationPreparationInput

    data class Shares(
        override val selection: MigrationSourceSelection,
        val resources: List<MigrationShareInput>,
    ) : MigrationPreparationInput
}

internal interface MigrationWorkflowService {
    /** Cleans invisible state left by a process interruption before accepting a new migration. */
    suspend fun recoverInterruptedOperations(): Int

    suspend fun prepare(
        input: MigrationPreparationInput,
        cancellationSignal: MigrationCancellationSignal,
        onTreeDiscoveryProgress: (discoveredFiles: Int, visitedFolders: Int) -> Unit,
        onSourceInspectionProgress: (inspectedSources: Int, totalSources: Int) -> Unit,
        onDuplicateProgress: MigrationBatchProgressListener,
    ): PreparedMigrationBatch

    suspend fun execute(
        prepared: PreparedMigrationBatch,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult

    suspend fun retryFailures(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult

    suspend fun retryIncomplete(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult
}

/** Thin Android adapter. All policy and retained state live in [MigrationWorkflowCoordinator]. */
internal class AndroidMigrationWorkflowService(
    context: Context,
    private val coordinator: AndroidBulkMigrationCoordinator =
        AndroidBulkMigrationCoordinator(context.applicationContext),
) : MigrationWorkflowService {
    override suspend fun recoverInterruptedOperations(): Int =
        coordinator.recoverInterruptedOperations()

    override suspend fun prepare(
        input: MigrationPreparationInput,
        cancellationSignal: MigrationCancellationSignal,
        onTreeDiscoveryProgress: (discoveredFiles: Int, visitedFolders: Int) -> Unit,
        onSourceInspectionProgress: (inspectedSources: Int, totalSources: Int) -> Unit,
        onDuplicateProgress: MigrationBatchProgressListener,
    ): PreparedMigrationBatch = when (input) {
        is MigrationPreparationInput.Files -> coordinator.previewFiles(
            resources = input.files.map { file ->
                MigrationFileResource(
                    uri = Uri.parse(file.source.value),
                    declaredContentType = file.declaredContentType,
                    grantFlags = file.grantFlags,
                )
            },
            cancellationSignal = cancellationSignal,
            progressListener = MigrationPreviewProgressListener(onSourceInspectionProgress),
            duplicateProgressListener = onDuplicateProgress,
        )
        is MigrationPreparationInput.Tree -> coordinator.previewTree(
            treeUri = Uri.parse(input.tree.value),
            grantFlags = input.grantFlags,
            cancellationSignal = cancellationSignal,
            discoveryProgressListener = MigrationTreeDiscoveryProgressListener(
                onTreeDiscoveryProgress,
            ),
            previewProgressListener = MigrationPreviewProgressListener(
                onSourceInspectionProgress,
            ),
            duplicateProgressListener = onDuplicateProgress,
        )
        is MigrationPreparationInput.Shares -> coordinator.previewShares(
            resources = input.resources.map { resource ->
                InboundShareResource(
                    uri = Uri.parse(resource.source.value),
                    declaredContentType = resource.declaredContentType,
                    grantFlags = resource.grantFlags,
                )
            },
            cancellationSignal = cancellationSignal,
            progressListener = MigrationPreviewProgressListener(onSourceInspectionProgress),
            duplicateProgressListener = onDuplicateProgress,
        )
    }

    override suspend fun execute(
        prepared: PreparedMigrationBatch,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult = coordinator.execute(
        prepared,
        decisions,
        cancellationSignal,
        progressListener,
    )

    override suspend fun retryFailures(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult = coordinator.retryFailures(
        prepared,
        previousReport,
        decisions,
        cancellationSignal,
        progressListener,
    )

    override suspend fun retryIncomplete(
        prepared: PreparedMigrationBatch,
        previousReport: MigrationBatchReport,
        decisions: MigrationDuplicateDecisions,
        cancellationSignal: MigrationCancellationSignal,
        progressListener: MigrationBatchProgressListener,
    ): MigrationExecutionResult = coordinator.retryIncomplete(
        prepared,
        previousReport,
        decisions,
        cancellationSignal,
        progressListener,
    )
}
