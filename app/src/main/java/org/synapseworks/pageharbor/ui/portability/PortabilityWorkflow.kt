package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

sealed interface PortabilityWorkflowState {
    data object Hidden : PortabilityWorkflowState

    data class MigrationSource(
        val selectedSource: ScannerMigrationSource = ScannerMigrationSource.OTHER,
        val actionsEnabled: Boolean = true,
    ) : PortabilityWorkflowState

    data class MigrationPreview(
        val preview: MigrationPreviewUiModel,
        val importEnabled: Boolean = true,
        val showAllReviewItems: Boolean = false,
    ) : PortabilityWorkflowState

    data class MigrationProgress(val progress: MigrationProgressUiModel) : PortabilityWorkflowState

    data class MigrationComplete(
        val report: MigrationCompletionUiModel,
        val showIssues: Boolean = false,
    ) : PortabilityWorkflowState

    data class BackupRestore(
        val status: BackupVerificationStatusUiModel,
        val actionsEnabled: Boolean = true,
    ) : PortabilityWorkflowState

    data class BackupReminder(
        val documentCount: Int,
        val pageCount: Int,
    ) : PortabilityWorkflowState

    data class RestorePassword(
        val validationMessage: String? = null,
        val actionsEnabled: Boolean = true,
    ) : PortabilityWorkflowState

    data class RestorePreview(
        val preview: RestorePreviewUiModel,
        val duplicateChoice: RestoreDuplicateChoice = RestoreDuplicateChoice.SKIP_EXACT,
    ) : PortabilityWorkflowState

    data class NewPhone(val actionsEnabled: Boolean = true) : PortabilityWorkflowState

    data class Operation(
        val title: String,
        val heading: String,
        val detail: String,
        val inProgress: Boolean,
        val isError: Boolean = false,
        val primaryActionLabel: String? = null,
    ) : PortabilityWorkflowState
}

/** UI callbacks are synchronous hand-off points; owners must copy and then wipe password arrays. */
data class PortabilityCallbacks(
    val onOpenMigration: () -> Unit = {},
    val onOpenBackupRestore: () -> Unit = {},
    val onOpenNewPhone: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onMigrationSourceSelected: (ScannerMigrationSource) -> Unit = {},
    val onSelectMigrationFiles: () -> Unit = {},
    val onSelectMigrationFolder: () -> Unit = {},
    val onReviewMigration: () -> Unit = {},
    val onMigrationDuplicateDecision: (Long, Boolean) -> Unit = { _, _ -> },
    val onImportMigration: () -> Unit = {},
    val onCancelMigration: () -> Unit = {},
    val onViewMigratedDocuments: () -> Unit = {},
    val onViewMigrationIssues: () -> Unit = {},
    val onRetryMigration: () -> Unit = {},
    val onCreateBackup: (encrypted: Boolean, password: CharArray?) -> Unit = { _, password ->
        password?.fill('\u0000')
    },
    val onSelectRestoreBackup: () -> Unit = {},
    val onExportLibrary: () -> Unit = {},
    val onRestorePassword: (CharArray) -> Unit = { password -> password.fill('\u0000') },
    val onRestoreDuplicateChoice: (RestoreDuplicateChoice) -> Unit = {},
    val onRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
    val onOperationPrimaryAction: () -> Unit = {},
    val onBackupReminderNow: () -> Unit = {},
    val onBackupReminderNotNow: () -> Unit = {},
)

@Composable
fun BackupReminderScreen(
    documentCount: Int,
    pageCount: Int,
    onBackupNow: () -> Unit,
    onNotNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortabilityScreen(title = "Keep your library portable", onBack = onBack, modifier = modifier) {
        PortabilityIntro(
            title = "Create a verified backup?",
            supportingText = "Your local RME library now contains $documentCount documents and " +
                "$pageCount pages. A portable backup helps you restore them on another phone.",
        )
        InformationCallout(
            text = "RME does not upload your documents. You choose where the backup is saved with " +
                "Android's system picker.",
        )
        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Back up now",
                    onClick = onBackupNow,
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = "Not now",
                    onClick = onNotNow,
                    style = PortabilityActionStyle.TEXT,
                ),
            ),
        )
    }
}

@Composable
fun RestorePasswordScreen(
    validationMessage: String?,
    actionsEnabled: Boolean,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    PortabilityScreen(title = "Encrypted RME backup", onBack = onBack, modifier = modifier) {
        PortabilityIntro(
            title = "Enter backup password",
            supportingText = "RME needs the password used on the old phone to authenticate and " +
                "decrypt this backup. The password is not stored.",
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            enabled = actionsEnabled,
            onValueChange = { password = it.take(MAX_BACKUP_PASSWORD_CHARACTERS) },
            label = { Text("Password") },
            singleLine = true,
            isError = validationMessage != null,
            supportingText = validationMessage?.let { message -> ({ Text(message) }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Continue",
                    enabled = actionsEnabled && password.isNotEmpty(),
                    style = PortabilityActionStyle.PRIMARY,
                    onClick = {
                        val characters = password.toCharArray()
                        try {
                            onSubmit(characters)
                        } finally {
                            characters.fill('\u0000')
                            password = ""
                        }
                    },
                ),
                PortabilityAction(
                    label = "Cancel",
                    enabled = actionsEnabled,
                    style = PortabilityActionStyle.TEXT,
                    onClick = onCancel,
                ),
            ),
        )
        InformationCallout(
            text = "A forgotten backup password cannot be recovered. A wrong password never " +
                "changes your existing library.",
        )
    }
}

@Composable
fun PortabilityOperationScreen(
    state: PortabilityWorkflowState.Operation,
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortabilityScreen(title = state.title, onBack = onBack, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium)) {
            if (state.inProgress) CircularProgressIndicator()
            Text(text = state.heading, style = MaterialTheme.typography.headlineSmall)
            InformationCallout(text = state.detail, isError = state.isError)
        }
        state.primaryActionLabel?.let { label ->
            PortabilityActions(
                actions = listOf(
                    PortabilityAction(
                        label = label,
                        onClick = onPrimaryAction,
                        style = PortabilityActionStyle.PRIMARY,
                    ),
                ),
            )
        }
    }
}

private const val MAX_BACKUP_PASSWORD_CHARACTERS = 1_024
