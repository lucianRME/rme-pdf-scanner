package org.synapseworks.pageharbor.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard

/** Copies in-memory text to the system clipboard without retaining it in the app. */
fun copyPlainTextToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text).apply { markSensitive() })
    return true
}

private fun ClipData.markSensitive() {
    if (Build.VERSION.SDK_INT >= 33) {
        description.extras = PersistableBundle(description.extras ?: PersistableBundle()).apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
}

/** Covers Compose selection and editable fields without changing their clipboard payloads. */
@Composable
internal fun SensitiveDocumentClipboard(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val sensitive = remember(clipboard) {
        object : Clipboard by clipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                clipEntry?.clipData?.markSensitive()
                clipboard.setClipEntry(clipEntry)
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides sensitive, content = content)
}
