package org.synapseworks.pageharbor.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.security.AppLockAuthenticatedChangeResult
import org.synapseworks.pageharbor.security.AppLockAuthenticationAvailability
import org.synapseworks.pageharbor.security.AppLockIssue
import org.synapseworks.pageharbor.security.AppLockPhase
import org.synapseworks.pageharbor.security.AppLockSetupResult
import org.synapseworks.pageharbor.security.AppLockState
import org.synapseworks.pageharbor.security.AutoLockTimeout
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

/** Replaces all protected content while the process-local app-lock state is locked. */
@Composable
fun AppLockScreen(
    state: AppLockState,
    authenticationAvailability: AppLockAuthenticationAvailability,
    onUnlock: () -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
    onExit: () -> Unit,
) {
    PageHarborTheme {
        var localMessage by remember { mutableStateOf<Int?>(null) }
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
                if (authenticationAvailability == AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK) {
                    Text(
                        text = stringResource(R.string.app_lock_no_device_lock_message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenDeviceSecuritySettings) {
                        Text(stringResource(R.string.app_lock_open_device_security_settings))
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = authenticationAvailability ==
                            AppLockAuthenticationAvailability.AVAILABLE,
                        onClick = onUnlock,
                    ) {
                        Text(stringResource(R.string.app_lock_unlock_with_device))
                    }
                }
                appLockIssueMessage(state.issue)?.let { message ->
                    Spacer(Modifier.height(12.dp))
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
                    )
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
    authenticationAvailability: AppLockAuthenticationAvailability,
    onBack: () -> Unit,
    onSetup: (AutoLockTimeout) -> AppLockSetupResult,
    onTimeoutChange: (AutoLockTimeout) -> AppLockAuthenticatedChangeResult,
    onDisable: () -> AppLockAuthenticatedChangeResult,
    onLockNow: () -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
) {
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
                Text(stringResource(R.string.app_lock_device_auth_description))
                if (authenticationAvailability == AppLockAuthenticationAvailability.NO_SECURE_DEVICE_LOCK) {
                    Text(
                        text = stringResource(R.string.app_lock_no_device_lock_message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenDeviceSecuritySettings,
                    ) {
                        Text(stringResource(R.string.app_lock_open_device_security_settings))
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = authenticationAvailability ==
                        AppLockAuthenticationAvailability.AVAILABLE,
                    onClick = {
                        message = when (onSetup(AutoLockTimeout.DEFAULT)) {
                            is AppLockSetupResult.Success -> R.string.app_lock_updated
                            AppLockSetupResult.DeviceAuthenticationUnavailable ->
                                R.string.app_lock_no_device_lock_message
                            AppLockSetupResult.StorageUnavailable ->
                                R.string.app_lock_error_unavailable
                        }
                    },
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
                Text(stringResource(R.string.app_lock_device_auth_description))
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
private fun appLockIssueMessage(issue: AppLockIssue?): String? = when (issue) {
    AppLockIssue.AUTHENTICATION_CANCELLED ->
        stringResource(R.string.app_lock_authentication_cancelled)
    AppLockIssue.AUTHENTICATION_FAILED ->
        stringResource(R.string.app_lock_authentication_failed)
    AppLockIssue.AUTHENTICATION_UNAVAILABLE ->
        stringResource(R.string.app_lock_error_unavailable)
    AppLockIssue.NO_SECURE_DEVICE_LOCK ->
        stringResource(R.string.app_lock_no_device_lock_message)
    AppLockIssue.STORAGE_UNAVAILABLE -> stringResource(R.string.app_lock_error_unavailable)
    null -> null
}

private fun AutoLockTimeout.labelResource(): Int = when (this) {
    AutoLockTimeout.IMMEDIATELY -> R.string.app_lock_timeout_immediately
    AutoLockTimeout.ONE_MINUTE -> R.string.app_lock_timeout_one_minute
    AutoLockTimeout.FIVE_MINUTES -> R.string.app_lock_timeout_five_minutes
}
