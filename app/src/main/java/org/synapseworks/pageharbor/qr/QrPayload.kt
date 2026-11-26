package org.synapseworks.pageharbor.qr

import java.net.URI
import java.util.Locale

/** Deliberately redacted in diagnostics; accepted content is never parsed into an executable action. */
internal class QrPayload private constructor(val text: String) {
    val display: String get() = buildString {
        text.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            if ((Character.isISOControl(cp) && cp != 10) || type == Character.FORMAT.toInt() ||
                cp == 0x2028 || cp == 0x2029) append("\\u{").append(cp.toString(16).uppercase(Locale.ROOT)).append('}')
            else appendCodePoint(cp)
        }
    }
    val destination: QrDestination? get() = QrDestination.parse(text)
    override fun toString() = "QrPayload(<redacted>)"
    companion object {
        const val MAX_UNITS = 8192
        const val MAX_BYTES = 32768
        fun accept(text: String): QrPayload? {
            if (text.isEmpty() || text.length > MAX_UNITS) return null
            var i = 0
            while (i < text.length) {
                val c = text[i++]
                if (Character.isHighSurrogate(c)) {
                    if (i >= text.length || !Character.isLowSurrogate(text[i++])) return null
                } else if (Character.isLowSurrogate(c)) return null
            }
            return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }?.let(::QrPayload)
        }
    }
}

internal class QrDestination private constructor(val value: String) {
    override fun toString() = "QrDestination(<redacted>)"
    companion object {
        fun parse(text: String): QrDestination? {
            if (text.isEmpty() || text.length > QrPayload.MAX_UNITS ||
                text.any { it.code !in 0x21..0x7e || it == '\\' }) return null
            // Encoded controls must not become another parser's line breaks or delimiters.
            if (Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
            return try {
                val uri = URI(text)
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
                val host = uri.host ?: return null
                if (scheme !in setOf("http", "https") || uri.isOpaque || uri.rawUserInfo != null ||
                    host.isEmpty() || uri.port != -1 && uri.port !in 1..65535) return null
                val authority = host + if (uri.port == -1) "" else ":${uri.port}"
                if (uri.rawAuthority != authority) return null
                QrDestination(scheme + text.substring(uri.scheme.length))
            } catch (_: Exception) { null }
        }
    }
}
