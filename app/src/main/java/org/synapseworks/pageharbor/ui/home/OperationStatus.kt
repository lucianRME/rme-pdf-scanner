package org.synapseworks.pageharbor.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

/** Compact, local feedback for active user-initiated work. */
@Composable
internal fun InlineOperationStatus(
    @StringRes messageRes: Int,
    vararg formatArgs: Any,
    modifier: Modifier = Modifier,
) {
    InlineOperationStatus(
        message = stringResource(messageRes, *formatArgs),
        modifier = modifier,
    )
}

/** Compact, local feedback for an already formatted message. */
@Composable
internal fun InlineOperationStatus(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
        )
        Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
