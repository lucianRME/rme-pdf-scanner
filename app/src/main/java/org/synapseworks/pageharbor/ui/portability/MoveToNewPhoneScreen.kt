package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@Composable
fun MoveToNewPhoneScreen(
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    PortabilityScreen(
        title = "Move to a new phone",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = "Your backup, your transfer method",
            supportingText = "RME creates and restores the backup locally. It does not upload, store, " +
                "or transport the file between phones.",
        )

        InformationCallout(
            text = "After the old phone verifies the backup, move that file with a storage or file " +
                "provider you choose in Android's system picker. Choose a location you can also " +
                "access from the new phone.",
        )

        DeviceStepsCard(
            title = "On your old phone",
            steps = listOf(
                "Create an RME backup.",
                "Optionally protect it with a password.",
                "Wait for RME to reopen and verify the backup.",
                "Save or copy the verified file using your chosen storage or file provider.",
            ),
        )
        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Create backup",
                    onClick = onCreateBackup,
                    enabled = actionsEnabled,
                    style = PortabilityActionStyle.PRIMARY,
                ),
            ),
        )

        DeviceStepsCard(
            title = "On your new phone",
            steps = listOf(
                "Install RME.",
                "Choose Restore backup and select the transferred file.",
                "Enter the backup password if you used one.",
                "Let RME verify the complete backup.",
                "Review the preview and duplicate choices, then restore.",
            ),
        )
        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Restore backup",
                    onClick = onRestoreBackup,
                    enabled = actionsEnabled,
                ),
            ),
        )

        Text(
            text = "Keep the old phone's library and verified backup until you have checked the " +
                "restored documents on the new phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceStepsCard(
    title: String,
    steps: List<String>,
) {
    PortabilityCard {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = step,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
