package org.synapseworks.pageharbor.ui.ocr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.ocr.OcrModelFailure
import org.synapseworks.pageharbor.ocr.OcrModelState
import org.synapseworks.pageharbor.ocr.OcrOperationSelection
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ocr.OcrScriptRecommendation
import org.synapseworks.pageharbor.ocr.OcrScriptSelection
import org.synapseworks.pageharbor.ocr.resolve

@Composable
fun OcrLanguageSettingsScreen(
    selection: OcrScriptSelection,
    recommendation: OcrScriptRecommendation,
    modelStates: Map<OcrScript, OcrModelState>,
    onSelectionChanged: (OcrScriptSelection) -> Unit,
    onInstall: (OcrScript) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { onRefresh() }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_back_action),
                        )
                    }
                    Text(
                        text = stringResource(R.string.ocr_language_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.ocr_language_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            OcrSelectionRow(
                title = stringResource(R.string.ocr_language_automatic),
                detail = stringResource(
                    R.string.ocr_language_recommended,
                    scriptName(recommendation.script),
                ),
                selected = selection == OcrScriptSelection.Automatic,
                onClick = { onSelectionChanged(OcrScriptSelection.Automatic) },
            )
            OcrScript.entries.forEach { script ->
                HorizontalDivider()
                OcrModelRow(
                    script = script,
                    selected = selection == OcrScriptSelection.Explicit(script),
                    state = modelStates[script] ?: defaultModelState(script),
                    onSelect = { onSelectionChanged(OcrScriptSelection.Explicit(script)) },
                    onInstall = { onInstall(script) },
                )
            }
            Text(
                text = stringResource(R.string.ocr_model_privacy_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
fun OcrOperationDialog(
    defaultSelection: OcrScriptSelection,
    recommendation: OcrScriptRecommendation,
    modelStates: Map<OcrScript, OcrModelState>,
    onInstall: (OcrScript) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (OcrOperationSelection, Boolean) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf("DEFAULT") }
    var useAsDefault by rememberSaveable { mutableStateOf(false) }
    val operationSelection = selectedId.toOperationSelection()
    val resolvedScript = when (operationSelection) {
        OcrOperationSelection.UseDefault -> defaultSelection.resolve(recommendation)
        is OcrOperationSelection.Override -> operationSelection.script
    }
    val state = modelStates[resolvedScript] ?: defaultModelState(resolvedScript)
    val ready = resolvedScript == OcrScript.LATIN || state == OcrModelState.Installed
    LaunchedEffect(Unit) { onRefresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ocr_operation_language_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OcrChoiceRow(
                    title = stringResource(R.string.ocr_language_use_default),
                    detail = stringResource(
                        R.string.ocr_language_default_resolves_to,
                        scriptName(resolvedDefaultScript(defaultSelection, recommendation)),
                    ),
                    selected = selectedId == "DEFAULT",
                    onClick = { selectedId = "DEFAULT"; useAsDefault = false },
                )
                OcrScript.entries.forEach { script ->
                    OcrChoiceRow(
                        title = scriptName(script),
                        detail = scriptExamples(script),
                        selected = selectedId == script.stableId,
                        onClick = { selectedId = script.stableId },
                    )
                }
                if (selectedId != "DEFAULT") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { useAsDefault = !useAsDefault }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = useAsDefault, onCheckedChange = null)
                        Text(stringResource(R.string.ocr_language_use_as_default))
                    }
                }
                if (!ready) {
                    Text(
                        text = modelStateLabel(state),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.ocr_model_privacy_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (ready) {
                Button(onClick = { onConfirm(operationSelection, useAsDefault) }) {
                    Text(stringResource(R.string.ocr_operation_start))
                }
            } else {
                Button(
                    enabled = state.canRequestInstall(),
                    onClick = { onInstall(resolvedScript) },
                ) {
                    Text(
                        if (state.isFailure()) stringResource(R.string.ocr_model_retry)
                        else stringResource(R.string.ocr_model_download_action),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun OcrModelRow(
    script: OcrScript,
    selected: Boolean,
    state: OcrModelState,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(scriptName(script)) },
        supportingContent = {
            Column {
                Text(scriptExamples(script))
                Text(modelStateLabel(state), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            when {
                state.isBusy() -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                script != OcrScript.LATIN && state.canRequestInstall() -> TextButton(onClick = onInstall) {
                    Text(
                        if (state.isFailure()) stringResource(R.string.ocr_model_retry)
                        else stringResource(R.string.ocr_model_download_short),
                    )
                }
            }
        },
    )
}

@Composable
private fun OcrSelectionRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) = ListItem(
    modifier = Modifier.clickable(role = Role.RadioButton, onClick = onClick),
    leadingContent = { RadioButton(selected = selected, onClick = null) },
    headlineContent = { Text(title) },
    supportingContent = { Text(detail) },
)

@Composable
private fun OcrChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun scriptName(script: OcrScript): String = stringResource(
    when (script) {
        OcrScript.LATIN -> R.string.ocr_language_latin
        OcrScript.CHINESE -> R.string.ocr_language_chinese
        OcrScript.JAPANESE -> R.string.ocr_language_japanese
        OcrScript.KOREAN -> R.string.ocr_language_korean
        OcrScript.DEVANAGARI -> R.string.ocr_language_devanagari
    },
)

@Composable
private fun scriptExamples(script: OcrScript): String = stringResource(
    when (script) {
        OcrScript.LATIN -> R.string.ocr_language_latin_examples
        OcrScript.CHINESE -> R.string.ocr_language_chinese_examples
        OcrScript.JAPANESE -> R.string.ocr_language_japanese_examples
        OcrScript.KOREAN -> R.string.ocr_language_korean_examples
        OcrScript.DEVANAGARI -> R.string.ocr_language_devanagari_examples
    },
)

@Composable
private fun modelStateLabel(state: OcrModelState): String = when (state) {
    OcrModelState.Bundled -> stringResource(R.string.ocr_model_built_in)
    OcrModelState.Installed -> stringResource(R.string.ocr_model_installed)
    OcrModelState.NotInstalled -> stringResource(R.string.ocr_model_not_installed)
    OcrModelState.Checking, OcrModelState.StatusUnknown -> stringResource(R.string.ocr_model_checking)
    OcrModelState.Pending -> stringResource(R.string.ocr_model_pending)
    is OcrModelState.Downloading -> if (
        state.downloadedBytes != null && state.totalBytes != null
    ) {
        stringResource(
            R.string.ocr_model_downloading_bytes,
            state.downloadedBytes,
            state.totalBytes,
        )
    } else {
        stringResource(R.string.ocr_model_downloading)
    }
    OcrModelState.Paused -> stringResource(R.string.ocr_model_paused)
    OcrModelState.Installing -> stringResource(R.string.ocr_model_installing)
    OcrModelState.Canceled -> stringResource(R.string.ocr_model_canceled)
    OcrModelState.Unsupported -> stringResource(R.string.ocr_model_play_services_unavailable)
    is OcrModelState.Failed -> failureLabel(state.reason)
    is OcrModelState.RetryableFailure -> failureLabel(state.reason)
}

@Composable
private fun failureLabel(reason: OcrModelFailure): String = stringResource(
    when (reason) {
        OcrModelFailure.GOOGLE_PLAY_SERVICES_UNAVAILABLE ->
            R.string.ocr_model_play_services_unavailable
        OcrModelFailure.AVAILABILITY_CHECK_FAILED -> R.string.ocr_model_check_failed
        OcrModelFailure.INSTALLATION_FAILED -> R.string.ocr_model_install_failed
    },
)

private fun defaultModelState(script: OcrScript): OcrModelState =
    if (script == OcrScript.LATIN) OcrModelState.Bundled else OcrModelState.StatusUnknown

private fun OcrModelState.isBusy(): Boolean = this == OcrModelState.Checking ||
    this == OcrModelState.Pending || this is OcrModelState.Downloading ||
    this == OcrModelState.Installing

private fun OcrModelState.isFailure(): Boolean = this is OcrModelState.Failed ||
    this is OcrModelState.RetryableFailure || this == OcrModelState.Canceled ||
    this == OcrModelState.Paused

private fun OcrModelState.canRequestInstall(): Boolean = when (this) {
    OcrModelState.NotInstalled,
    OcrModelState.Canceled,
    OcrModelState.Paused,
    OcrModelState.StatusUnknown,
    is OcrModelState.Failed,
    is OcrModelState.RetryableFailure,
    -> true
    else -> false
}

private fun String.toOperationSelection(): OcrOperationSelection =
    OcrScript.fromStableId(this)?.let(OcrOperationSelection::Override)
        ?: OcrOperationSelection.UseDefault

private fun resolvedDefaultScript(
    selection: OcrScriptSelection,
    recommendation: OcrScriptRecommendation,
): OcrScript = selection.resolve(recommendation)
