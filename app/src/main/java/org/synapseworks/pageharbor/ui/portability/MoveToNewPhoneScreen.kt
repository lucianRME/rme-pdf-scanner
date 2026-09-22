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
            title = "Move your library safely",
            supportingText = "Create a verified backup, then restore it on your new phone.",
        )

        InformationCallout(
            text = "RME does not upload your documents or use an RME cloud service.",
        )

        DeviceStepsCard(
            title = "On your old phone",
            steps = listOf(
                "Create an RME backup.",
                "Save or copy the verified backup.",
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
                "Select the backup and choose Restore backup.",
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
