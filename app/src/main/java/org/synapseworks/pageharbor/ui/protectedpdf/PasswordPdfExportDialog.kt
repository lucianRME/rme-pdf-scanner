package org.synapseworks.pageharbor.ui.protectedpdf

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportState
import org.synapseworks.pageharbor.document.protectedpdf.isSupportedPdfPassword

/** Password-only UI: no saved-state/password persistence and no future-tool routes. */
@Composable
fun PasswordPdfExportDialog(
    state: PasswordPdfExportState,
    onDismiss: () -> Unit,
    onOptionsChanged: () -> Unit,
    onPrepare: (CharArray) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    if (!state.visible) return
    var password by remember(state.source) { mutableStateOf("") }
    var confirmation by remember(state.source) { mutableStateOf("") }
    var oversized by remember(state.source) { mutableStateOf(false) }
    var confirmationOversized by remember(state.source) { mutableStateOf(false) }
    DisposableEffect(state.source) {
        onDispose { password = ""; confirmation = "" }
    }
    val supported = remember(password) {
        val chars = password.toCharArray()
        try { isSupportedPdfPassword(chars) } finally { chars.fill('\u0000') }
    }
    val enabled = !state.busy && state.source != null
    val mismatch = confirmation.isNotEmpty() && confirmation != password
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().imePadding().padding(12.dp)
                .widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.95f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.protect_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.protect_intro))
                Text(stringResource(R.string.protect_export_boundary), style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.protect_working), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                state.message?.let { message ->
                    Text(stringResource(message), color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.source != null) {
                    RmeTextField(
                        value = password,
                        onValueChange = {
                            onOptionsChanged()
                            oversized = it.length > 512
                            if (!oversized) password = it
                        },
                        label = { Text(stringResource(R.string.protect_password_label)) },
                        supportingText = { Text(stringResource(if (oversized || (password.isNotEmpty() && !supported))
                            R.string.protect_password_error else R.string.protect_password_policy)) },
                        isError = oversized || (password.isNotEmpty() && !supported),
                        enabled = enabled, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("protect-password"),
                    )
                    RmeTextField(
                        value = confirmation,
                        onValueChange = {
                            onOptionsChanged()
                            confirmationOversized = it.length > 512
                            if (!confirmationOversized) confirmation = it
                        },
                        label = { Text(stringResource(R.string.protect_confirmation_label)) },
                        supportingText = {
                            if (confirmationOversized || mismatch) Text(stringResource(
                                if (confirmationOversized) R.string.protect_password_error else R.string.protect_confirmation_error,
                            ))
                        },
                        isError = confirmationOversized || mismatch, enabled = enabled, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("protect-confirmation"),
                    )
                    RmePrimaryButton(
                        enabled = enabled && supported && !oversized && !confirmationOversized && password == confirmation,
                        onClick = {
                            val owned = password.toCharArray()
                            password = ""; confirmation = ""
                            keyboard?.hide(); focus.clearFocus()
                            onPrepare(owned)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("protect-prepare"),
                    ) { Text(stringResource(R.string.protect_prepare)) }
                    if (state.output != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.protect_ready, state.sourcePages),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        RmePrimaryButton(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.protect_save))
                        }
                        RmeSecondaryButton(onClick = onShare, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.protect_share))
                        }
                        Text(stringResource(R.string.protect_password_separate), style = MaterialTheme.typography.bodySmall)
                    }
                }
                RmeTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.busy) R.string.protect_cancel else R.string.protect_close))
                }
            }
        }
    }
}
