package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

/**
 * The caller must keep [encryption] in transient, non-saveable state and clear password fields after
 * backup starts, is cancelled, or leaves this screen.
 */
@Composable
fun BackupRestoreScreen(
    status: BackupVerificationStatusUiModel,
    encryption: BackupEncryptionUiState,
    onEncryptionEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreBackup: () -> Unit,
    onExportLibrary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    val backupInProgress = status is BackupVerificationStatusUiModel.InProgress
    PortabilityScreen(
        title = "Backup & restore",
        onBack = onBack,
        modifier = modifier,
    ) {
        BackupStatusCard(status = status)

        PortabilitySection(title = "Portable backup") {
            Text(
                text = "Create a complete RME backup in a location you choose through Android's " +
                    "system picker. Every backup is reopened and verified before RME reports success.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackupEncryptionCard(
                state = encryption,
                enabled = actionsEnabled && !backupInProgress,
                onEnabledChange = onEncryptionEnabledChange,
                onPasswordChange = onPasswordChange,
                onConfirmationChange = onConfirmationChange,
            )
        }

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Back up now",
                    onClick = onBackupNow,
                    enabled = actionsEnabled && !backupInProgress && encryption.canCreateBackup,
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = "Restore backup",
                    onClick = onRestoreBackup,
                    enabled = actionsEnabled && !backupInProgress,
                ),
                PortabilityAction(
                    label = "Export library",
                    onClick = onExportLibrary,
                    enabled = actionsEnabled && !backupInProgress,
                ),
            ),
        )

        InformationCallout(
            text = "Export library creates ordinary PDFs and folders for long-term access. An RME " +
                "backup additionally preserves editable library structure for restoration in RME.",
        )
    }
}

@Composable
private fun BackupStatusCard(status: BackupVerificationStatusUiModel) {
    PortabilitySection(title = "Backup status") {
        val title: String
        val detail: String
        val isError: Boolean
        when (status) {
            BackupVerificationStatusUiModel.NeverBackedUp -> {
                title = "Never backed up"
                detail = "Create and keep a verified backup in a destination you control."
                isError = false
            }

            is BackupVerificationStatusUiModel.Verified -> {
                title = "Last verified backup: ${status.lastVerified}"
                detail = if (status.libraryChangedSince) {
                    "Your library has changed since that backup."
                } else {
                    "That backup was reopened and verified successfully."
                }
                isError = false
            }

            is BackupVerificationStatusUiModel.InProgress -> {
                title = "Backup in progress"
                detail = status.stage
                isError = false
            }

            is BackupVerificationStatusUiModel.Failed -> {
                title = "Backup not verified"
                detail = status.safeReason
                isError = true
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            colors = CardDefaults.cardColors(
                containerColor = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PageHarborSpacing.large),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BackupEncryptionCard(
    state: BackupEncryptionUiState,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val mismatch = state.confirmation.isNotEmpty() && state.password != state.confirmation
    PortabilityCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.enabled,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            ) {
                Text(
                    text = "Encrypt backup with password",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Protect the complete backup, including document and folder metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.enabled,
                enabled = enabled,
                onCheckedChange = null,
            )
        }

        if (state.enabled) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.password,
                enabled = enabled,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.confirmation,
                enabled = enabled,
                onValueChange = onConfirmationChange,
                label = { Text("Confirm password") },
                singleLine = true,
                isError = mismatch || state.validationMessage != null,
                supportingText = when {
                    state.validationMessage != null -> ({ Text(state.validationMessage) })
                    mismatch -> ({ Text("Passwords do not match.") })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )
            InformationCallout(
                text = "RME never stores this password. To restore on another phone, you need the " +
                    "backup file and this exact password. A forgotten password cannot be recovered.",
            )
        }
    }
}

@Composable
fun RestorePreviewScreen(
    preview: RestorePreviewUiModel,
    duplicateChoice: RestoreDuplicateChoice,
    onDuplicateChoiceChange: (RestoreDuplicateChoice) -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortabilityScreen(
        title = "Restore RME backup",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = "Restore preview",
            supportingText = "Review the fully inspected backup before anything is added to your " +
                "library. Existing documents remain untouched until final activation.",
        )

        SummaryMetrics(
            metrics = listOf(
                "Documents" to preview.documentCount.toString(),
                "Pages" to preview.pageCount.toString(),
                "Folders" to preview.folderCount.toString(),
                "Exact duplicates" to preview.exactDuplicateCount.toString(),
                "Possible duplicates" to preview.possibleDuplicateCount.toString(),
                "Estimated storage" to preview.estimatedStorage,
                "Created" to preview.createdAt,
            ),
        )

        if (preview.fullyVerified) {
            InformationCallout(
                text = "Backup validation completed successfully. RME will still stage and activate " +
                    "the restore as one recoverable operation.",
            )
        } else {
            InformationCallout(
                text = "This backup has not completed validation. Restore remains unavailable.",
                isError = true,
            )
        }

        if (preview.existingLibraryHasContent) {
            PortabilitySection(title = "Duplicate handling") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    RestoreDuplicateChoice.entries.forEach { choice ->
                        RestoreDuplicateOption(
                            choice = choice,
                            selected = duplicateChoice == choice,
                            onClick = { onDuplicateChoiceChange(choice) },
                        )
                    }
                }
                if (preview.possibleDuplicateCount > 0) {
                    Text(
                        text = "Possible duplicates are not silently discarded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Restore",
                    onClick = onRestore,
                    enabled = preview.fullyVerified && preview.documentCount > 0,
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = "Cancel",
                    onClick = onCancel,
                    style = PortabilityActionStyle.TEXT,
                ),
            ),
        )
    }
}

@Composable
private fun RestoreDuplicateOption(
    choice: RestoreDuplicateChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PageHarborSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            ) {
                Text(text = choice.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = choice.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
