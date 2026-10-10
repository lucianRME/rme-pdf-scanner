package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val RmeButtonShape = RoundedCornerShape(12.dp)

@Composable
fun RmePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val rmeColors = MaterialTheme.rmeColors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.primaryActionHeight),
        enabled = enabled,
        shape = RmeButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = rmeColors.disabledBackground,
            disabledContentColor = rmeColors.textDisabled,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        content = { content() },
    )
}

@Composable
fun RmeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    val rmeColors = MaterialTheme.rmeColors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.primaryActionHeight),
        enabled = enabled,
        shape = RmeButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = rmeColors.surfaceHigh,
            contentColor = contentColor,
            disabledContainerColor = rmeColors.disabledBackground,
            disabledContentColor = rmeColors.textDisabled,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        content = { content() },
    )
}

@Composable
fun RmeTonalChoice(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheckmark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val rmeColors = MaterialTheme.rmeColors
    val containerColor = when {
        !enabled -> rmeColors.disabledBackground
        selected -> rmeColors.selectedBackground
        else -> rmeColors.surfaceLow
    }
    val contentColor = when {
        !enabled -> rmeColors.textDisabled
        selected -> rmeColors.selectedForeground
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = PageHarborLayout.minimumTouchTarget)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            },
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected && showCheckmark) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
                )
            }
            content()
        }
    }
}
