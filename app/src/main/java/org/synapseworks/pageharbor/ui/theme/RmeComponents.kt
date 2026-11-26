package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val ActionPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Wrap sibling actions when translated labels or larger text need more room. */
@Composable
fun RmeActionRow(content: @Composable FlowRowScope.() -> Unit) {
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val stack = density.fontScale >= 1.5f && windowWidth < with(density) { 480.dp.roundToPx() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        maxItemsInEachRow = if (stack) 1 else Int.MAX_VALUE,
        content = content,
    )
}

@Composable
fun RmePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = ActionPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.rmeColors
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.primaryActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) scheme.error else scheme.primary,
            contentColor = if (destructive) scheme.onError else scheme.onPrimary,
            disabledContainerColor = colors.disabledBackground,
            disabledContentColor = colors.textDisabled,
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun RmeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = ActionPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.rmeColors
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.primaryActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surfaceHigh,
            contentColor = contentColor,
            disabledContainerColor = colors.disabledBackground,
            disabledContentColor = colors.textDisabled,
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun RmeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.minimumTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.rmeColors.textDisabled,
        ),
        content = content,
    )
}

@Composable
fun RmeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.sizeIn(
            minWidth = PageHarborLayout.minimumTouchTarget,
            minHeight = PageHarborLayout.minimumTouchTarget,
        ),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            disabledContentColor = MaterialTheme.rmeColors.textDisabled,
        ),
        content = content,
    )
}

@Composable
fun RmeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
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

@Composable
fun RmeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val rmeColors = MaterialTheme.rmeColors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = PageHarborLayout.minimumTouchTarget),
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = rmeColors.surfaceLow,
            unfocusedContainerColor = rmeColors.surfaceLow,
            disabledContainerColor = rmeColors.disabledBackground,
            errorContainerColor = rmeColors.surfaceLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            errorBorderColor = MaterialTheme.colorScheme.error,
            disabledBorderColor = rmeColors.divider,
            disabledTextColor = rmeColors.textDisabled,
            disabledLabelColor = rmeColors.textDisabled,
            unfocusedLeadingIconColor = rmeColors.iconSecondary,
            unfocusedTrailingIconColor = rmeColors.iconSecondary,
        ),
    )
}

@Composable
fun RmeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    RmeTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { androidx.compose.material3.Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                RmeIconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = clearContentDescription,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RmeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rmeColors = MaterialTheme.rmeColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = rmeColors.sheetBackground,
        dragHandle = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(rmeColors.iconSecondary, CircleShape),
            )
        },
        content = content,
    )
}
