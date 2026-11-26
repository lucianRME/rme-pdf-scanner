package org.synapseworks.pageharbor.protectedpdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

/** Synthetic read-grant receiver; no received password, plaintext extraction or diagnostic payload. */
class M05ShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val output = File(cacheDir, "m05-received-encrypted.pdf")
        readGrantObserved = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        if (uri != null && uri.scheme == "content") {
            contentResolver.openInputStream(uri)?.use { input -> output.outputStream().use { input.copyTo(it) } }
            passwordRequired = try {
                PDDocument.load(output).use { }
                false
            } catch (_: InvalidPasswordException) { true }
            receivedCount++
        }
        finish()
    }
    companion object {
        @Volatile var receivedCount = 0
        @Volatile var passwordRequired = false
        @Volatile var readGrantObserved = false
    }
}
