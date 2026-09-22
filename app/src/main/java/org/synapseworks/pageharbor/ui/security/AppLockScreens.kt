package org.synapseworks.pageharbor.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.security.AppLockAuthenticatedChangeResult
import org.synapseworks.pageharbor.security.AppLockBiometricAvailability
import org.synapseworks.pageharbor.security.AppLockIssue
import org.synapseworks.pageharbor.security.AppLockPhase
import org.synapseworks.pageharbor.security.AppLockSetupResult
import org.synapseworks.pageharbor.security.AppLockState
import org.synapseworks.pageharbor.security.AppLockUnlockResult
import org.synapseworks.pageharbor.security.AutoLockTimeout
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

/** Replaces all protected content while the process-local app-lock state is locked. */
@Composable
fun AppLockScreen(
    state: AppLockState,
    biometricAvailability: AppLockBiometricAvailability,
    onUnlockWithPin: (CharArray) -> AppLockUnlockResult,
    onUnlockWithBiometric: () -> Unit,
    onExit: () -> Unit,
) {
    PageHarborTheme {
        var pin by rememberSaveable { mutableStateOf("") }
        var localMessage by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(state.phase) {
            if (state.phase != AppLockPhase.LOCKED) pin = ""
        }
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    modifier = Modifier.semantics { heading() },
                    text = stringResource(R.string.app_lock_locked_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.app_lock_locked_supporting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
                    label = { Text(stringResource(R.string.app_lock_pin_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                )
                appLockIssueMessage(state.issue, state.retryAfterMillis)?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                localMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    enabled = pin.length >= MIN_PIN_LENGTH,
                    onClick = {
                        val characters = pin.toCharArray()
                        val result = try {
                            onUnlockWithPin(characters)
                        } finally {
                            characters.fill('\u0000')
                            pin = ""
                        }
                        localMessage = when (result) {
                            AppLockUnlockResult.ACCEPTED,
                            AppLockUnlockResult.ALREADY_UNLOCKED,
                            -> null
                            AppLockUnlockResult.THROTTLED -> R.string.app_lock_error_throttled
                            AppLockUnlockResult.REJECTED -> R.string.app_lock_error_incorrect
                            else -> R.string.app_lock_error_unavailable
                        }
                    },
                ) {
                    Text(stringResource(R.string.app_lock_unlock_action))
                }
                if (
                    state.config.biometricEnabled &&
                    biometricAvailability == AppLockBiometricAvailability.AVAILABLE
                ) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        onClick = onUnlockWithBiometric,
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = stringResource(R.string.app_lock_biometric_unlock_action),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.app_lock_exit_action))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsScreen(
    state: AppLockState,
    biometricAvailability: AppLockBiometricAvailability,
    onBack: () -> Unit,
    onSetup: (CharArray, CharArray, AutoLockTimeout) -> AppLockSetupResult,
    onReplacePin: (CharArray, CharArray) -> AppLockAuthenticatedChangeResult,
    onTimeoutChange: (AutoLockTimeout) -> AppLockAuthenticatedChangeResult,
    onEnableBiometric: () -> AppLockAuthenticatedChangeResult,
    onDisableBiometric: () -> AppLockAuthenticatedChangeResult,
    onDisable: () -> AppLockAuthenticatedChangeResult,
    onLockNow: () -> Unit,
) {
    var pinDialog by remember { mutableStateOf<PinDialogMode?>(null) }
    var confirmDisable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_lock_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.library_cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (state.config.enabled) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (state.config.enabled) {
                            stringResource(R.string.app_lock_enabled_title)
                        } else {
                            stringResource(R.string.app_lock_disabled_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(stringResource(R.string.app_lock_disclosure_access))
                    Text(
                        text = stringResource(R.string.app_lock_disclosure_at_rest),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.app_lock_disclosure_recovery),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            message?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!state.config.enabled) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { pinDialog = PinDialogMode.Setup },
                ) {
                    Text(stringResource(R.string.app_lock_enable_action))
                }
            } else {
                Text(
                    modifier = Modifier.semantics { heading() },
                    text = stringResource(R.string.app_lock_timeout_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                AutoLockTimeout.entries.forEach { timeout ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.config.autoLockTimeout == timeout,
                            onClick = {
                                message = if (
                                    onTimeoutChange(timeout) ==
                                    AppLockAuthenticatedChangeResult.APPLIED
                                ) R.string.app_lock_updated else R.string.app_lock_error_unavailable
                            },
                        )
                        Text(text = stringResource(timeout.labelResource()))
                    }
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = biometricAvailability == AppLockBiometricAvailability.AVAILABLE ||
                        state.config.biometricEnabled,
                    onClick = {
                        val result = if (state.config.biometricEnabled) {
                            onDisableBiometric()
                        } else {
                            onEnableBiometric()
                        }
                        message = if (result == AppLockAuthenticatedChangeResult.APPLIED) {
                            R.string.app_lock_updated
                        } else {
                            R.string.app_lock_biometric_unavailable
                        }
                    },
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(
                            if (state.config.biometricEnabled) {
                                R.string.app_lock_disable_biometric_action
                            } else {
                                R.string.app_lock_enable_biometric_action
                            },
                        ),
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { pinDialog = PinDialogMode.Replace },
                ) {
                    Text(stringResource(R.string.app_lock_change_pin_action))
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = onLockNow) {
                    Text(stringResource(R.string.app_lock_lock_now_action))
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDisable = true },
                ) {
                    Text(stringResource(R.string.app_lock_disable_action))
                }
            }
        }
    }

    pinDialog?.let { mode ->
        PinSetupDialog(
            title = stringResource(
                if (mode == PinDialogMode.Setup) {
                    R.string.app_lock_create_pin_title
                } else {
                    R.string.app_lock_change_pin_action
                },
            ),
            onDismiss = { pinDialog = null },
            onSubmit = { pin, confirmation ->
                val result = if (mode == PinDialogMode.Setup) {
                    onSetup(pin, confirmation, AutoLockTimeout.DEFAULT) is AppLockSetupResult.Success
                } else {
                    onReplacePin(pin, confirmation) == AppLockAuthenticatedChangeResult.APPLIED
                }
                if (result) {
                    pinDialog = null
                    message = R.string.app_lock_updated
                }
                result
            },
        )
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(R.string.app_lock_disable_confirm_title)) },
            text = { Text(stringResource(R.string.app_lock_disable_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    message = if (onDisable() == AppLockAuthenticatedChangeResult.APPLIED) {
                        R.string.app_lock_updated
                    } else {
                        R.string.app_lock_error_unavailable
                    }
                    confirmDisable = false
                }) { Text(stringResource(R.string.app_lock_disable_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

@Composable
private fun PinSetupDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (CharArray, CharArray) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_lock_setup_supporting))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
                    label = { Text(stringResource(R.string.app_lock_new_pin_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it.filter(Char::isDigit).take(MAX_PIN_LENGTH)
                    },
                    label = { Text(stringResource(R.string.app_lock_confirm_pin_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = invalid,
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.app_lock_pin_requirements),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= MIN_PIN_LENGTH && confirmation.length >= MIN_PIN_LENGTH,
                onClick = {
                    val pinCharacters = pin.toCharArray()
                    val confirmationCharacters = confirmation.toCharArray()
                    val accepted = try {
                        onSubmit(pinCharacters, confirmationCharacters)
                    } finally {
                        pinCharacters.fill('\u0000')
                        confirmationCharacters.fill('\u0000')
                    }
                    if (accepted) {
                        pin = ""
                        confirmation = ""
                    } else {
                        invalid = true
                    }
                },
            ) { Text(stringResource(R.string.app_lock_save_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun appLockIssueMessage(issue: AppLockIssue?, retryAfterMillis: Long): String? = when (issue) {
    AppLockIssue.INCORRECT_PIN -> stringResource(R.string.app_lock_error_incorrect)
    AppLockIssue.PIN_THROTTLED -> stringResource(
        R.string.app_lock_error_throttled_seconds,
        ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L),
    )
    AppLockIssue.BIOMETRIC_CANCELLED -> stringResource(R.string.app_lock_biometric_cancelled)
    AppLockIssue.BIOMETRIC_FAILED -> stringResource(R.string.app_lock_biometric_failed)
    AppLockIssue.BIOMETRIC_KEY_INVALIDATED ->
        stringResource(R.string.app_lock_biometric_invalidated)
    AppLockIssue.BIOMETRIC_UNAVAILABLE,
    AppLockIssue.CREDENTIAL_UNAVAILABLE,
    AppLockIssue.STORAGE_UNAVAILABLE,
    -> stringResource(R.string.app_lock_error_unavailable)
    null -> null
}

private fun AutoLockTimeout.labelResource(): Int = when (this) {
    AutoLockTimeout.IMMEDIATELY -> R.string.app_lock_timeout_immediately
    AutoLockTimeout.ONE_MINUTE -> R.string.app_lock_timeout_one_minute
    AutoLockTimeout.FIVE_MINUTES -> R.string.app_lock_timeout_five_minutes
}

private enum class PinDialogMode { Setup, Replace }

private const val MIN_PIN_LENGTH = 6
private const val MAX_PIN_LENGTH = 32
