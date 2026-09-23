package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@Composable
fun MoveFromScannerScreen(
    selectedSource: ScannerMigrationSource,
    onSourceSelected: (ScannerMigrationSource) -> Unit,
    onSelectFiles: () -> Unit,
    onSelectFolder: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    PortabilityScreen(
        title = "Move from another scanner",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = "Choose your scanner",
            supportingText = "Choose where your documents are coming from.",
        )

        ScannerSourceChooser(
            selectedSource = selectedSource,
            onSourceSelected = onSourceSelected,
        )

        PortabilitySection(title = "Next step") {
            InformationCallout(text = selectedSource.guidance)
        }

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Select files",
                    onClick = onSelectFiles,
                    enabled = actionsEnabled,
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = "Select folder",
                    onClick = onSelectFolder,
                    enabled = actionsEnabled,
                ),
            ),
        )

        Text(
            text = "Choose files or a folder to review before import.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScannerSourceChooser(
    selectedSource: ScannerMigrationSource,
    onSourceSelected: (ScannerMigrationSource) -> Unit,
) {
    PortabilitySection(title = "Source app") {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            val useTwoColumns = maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.8f
            Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                ScannerMigrationSource.entries.chunked(if (useTwoColumns) 2 else 1).forEach { rowSources ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                    ) {
                        rowSources.forEach { source ->
                            ScannerSourceOption(
                                modifier = Modifier.weight(1f),
                                source = source,
                                selected = source == selectedSource,
                                onClick = { onSourceSelected(source) },
                            )
                        }
                        if (useTwoColumns && rowSources.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerSourceOption(
    source: ScannerMigrationSource,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.selectable(
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
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
            )
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = PageHarborSpacing.small),
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
