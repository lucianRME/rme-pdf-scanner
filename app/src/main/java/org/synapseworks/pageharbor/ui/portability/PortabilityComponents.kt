package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortabilityScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = title,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val horizontalPadding = when {
                maxWidth >= 840.dp -> PageHarborLayout.expandedScreenHorizontalPadding
                maxWidth >= 600.dp -> PageHarborLayout.mediumScreenHorizontalPadding
                else -> PageHarborLayout.compactScreenHorizontalPadding
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = PageHarborLayout.portabilityContentMaxWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = PageHarborLayout.compactScreenVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(
                    if (LocalDensity.current.fontScale >= 1.8f) {
                        PageHarborSpacing.small
                    } else {
                        PageHarborSpacing.large
                    },
                ),
                content = content,
            )
        }
    }
}

@Composable
internal fun PortabilityIntro(
    title: String,
    supportingText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PortabilitySection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
internal fun PortabilityCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PageHarborSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            content = content,
        )
    }
}

@Composable
internal fun InformationCallout(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Text(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun SummaryMetrics(
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val singleColumn = maxWidth < 520.dp || LocalDensity.current.fontScale >= 1.8f
        Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
            metrics.chunked(if (singleColumn) 1 else 2).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    rowMetrics.forEach { (label, value) ->
                        MetricCard(
                            modifier = Modifier.weight(1f),
                            label = label,
                            value = value,
                        )
                    }
                    if (!singleColumn && rowMetrics.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal enum class PortabilityActionStyle {
    PRIMARY,
    OUTLINED,
    TEXT,
}

internal data class PortabilityAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val style: PortabilityActionStyle = PortabilityActionStyle.OUTLINED,
)

@Composable
internal fun PortabilityActions(
    actions: List<PortabilityAction>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stackActions = maxWidth < 520.dp || LocalDensity.current.fontScale >= 1.8f
        if (stackActions) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                actions.forEach { action ->
                    PortabilityActionButton(
                        modifier = Modifier.fillMaxWidth(),
                        action = action,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                actions.forEach { action ->
                    PortabilityActionButton(
                        modifier = Modifier.weight(1f),
                        action = action,
                    )
                }
            }
        }
    }
}

@Composable
private fun PortabilityActionButton(
    action: PortabilityAction,
    modifier: Modifier,
) {
    when (action.style) {
        PortabilityActionStyle.PRIMARY -> Button(
            modifier = modifier,
            enabled = action.enabled,
            onClick = action.onClick,
        ) {
            Text(action.label)
        }

        PortabilityActionStyle.OUTLINED -> OutlinedButton(
            modifier = modifier,
            enabled = action.enabled,
            onClick = action.onClick,
        ) {
            Text(action.label)
        }

        PortabilityActionStyle.TEXT -> TextButton(
            modifier = modifier,
            enabled = action.enabled,
            onClick = action.onClick,
        ) {
            Text(action.label)
        }
    }
}

internal fun countLabel(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
