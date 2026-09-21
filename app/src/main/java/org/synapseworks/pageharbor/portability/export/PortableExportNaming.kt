package org.synapseworks.pageharbor.portability.export

import java.text.Normalizer
import java.util.Locale

/** Produces portable, human-readable names without exposing internal document IDs. */
object PortableExportNaming {
    private const val DEFAULT_BASENAME = "Document"
    private const val MAX_BASENAME_LENGTH = 120
    private val unsafeCharacters = Regex("[\\u0000-\\u001f\\u007f\\\\/:*?\"<>|]")
    private val repeatedWhitespace = Regex("\\s+")
    private val reservedWindowsNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { index ->
            add("COM$index")
            add("LPT$index")
        }
    }

    fun safeBaseName(raw: String, fallback: String = DEFAULT_BASENAME): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace(unsafeCharacters, "_")
            .replace(repeatedWhitespace, " ")
            .trim()
            .trimEnd('.', ' ')
            .take(MAX_BASENAME_LENGTH)
            .trimEnd('.', ' ')
        val candidate = normalized.ifBlank {
            fallback.replace(unsafeCharacters, "_").trim().ifBlank { DEFAULT_BASENAME }
        }
        return if (candidate.uppercase(Locale.ROOT) in reservedWindowsNames) {
            "_$candidate"
        } else {
            candidate
        }
    }

    /** Allocates deterministic, case-insensitive siblings: Name.pdf, Name (2).pdf, ... */
    fun allocateFileName(
        title: String,
        extension: String,
        usedNames: MutableSet<String>,
    ): String {
        val safeExtension = extension.trim().trimStart('.').lowercase(Locale.ROOT)
        require(safeExtension.matches(Regex("[a-z0-9]{1,12}")))
        val base = safeBaseName(title)
        var suffix = 1
        while (true) {
            val candidate = if (suffix == 1) {
                "$base.$safeExtension"
            } else {
                "$base ($suffix).$safeExtension"
            }
            if (usedNames.add(candidate.lowercase(Locale.ROOT))) return candidate
            suffix += 1
        }
    }

    fun allocateFolderName(
        requestedName: String,
        usedNames: MutableSet<String>,
    ): String {
        val base = safeBaseName(requestedName, fallback = "Folder")
        var suffix = 1
        while (true) {
            val candidate = if (suffix == 1) base else "$base ($suffix)"
            if (usedNames.add(candidate.lowercase(Locale.ROOT))) return candidate
            suffix += 1
        }
    }
}
