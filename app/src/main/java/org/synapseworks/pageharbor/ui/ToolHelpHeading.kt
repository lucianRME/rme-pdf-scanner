package org.synapseworks.pageharbor.ui

import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R

@Composable
internal fun ToolHelpHeading(@StringRes titleId: Int, @StringRes detailsId: Int) {
    val title = stringResource(titleId)
    val about = stringResource(R.string.specialist_about_tool, title)
    var showHelp by rememberSaveable(titleId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        RmeIconButton(onClick = { showHelp = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Info, contentDescription = about)
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(about) },
            text = { Text(stringResource(detailsId), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                RmeTextButton(onClick = { showHelp = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}
