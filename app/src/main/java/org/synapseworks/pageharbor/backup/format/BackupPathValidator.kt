package org.synapseworks.pageharbor.backup.format

import java.text.Normalizer
import java.util.Locale

object BackupPathValidator {
    private const val MAX_PATH_CHARACTERS = 512
    private const val MAX_SEGMENT_CHARACTERS = 160
    private val windowsAbsolute = Regex("[A-Za-z]:/.*")
    private val stableId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    private val pageAsset = Regex(
        "documents/([A-Za-z0-9][A-Za-z0-9_-]{0,127})/pages/([0-9]+)-" +
            "([A-Za-z0-9][A-Za-z0-9_-]{0,127})\\.(jpg|png|webp)",
    )
    private val sourceAsset = Regex(
        "documents/([A-Za-z0-9][A-Za-z0-9_-]{0,127})/sources/" +
            "([A-Za-z0-9][A-Za-z0-9_-]{0,127})\\.pdf",
    )

    private val fixedEntries = setOf(
        RME_BACKUP_MANIFEST_PATH,
        RME_BACKUP_FOLDERS_PATH,
        RME_BACKUP_DOCUMENTS_PATH,
        RME_BACKUP_PAGES_PATH,
        RME_BACKUP_SOURCE_ASSETS_PATH,
        RME_BACKUP_CHECKSUMS_PATH,
    )

    fun requireValidArchivePath(path: String): String {
        if (path.isEmpty() || path.length > MAX_PATH_CHARACTERS) invalid(path)
        if (path.startsWith('/') || path.endsWith('/') || windowsAbsolute.matches(path)) invalid(path)
        if ('\\' in path || '\u0000' in path || '\r' in path || '\n' in path) invalid(path)
        if (path != path.trim()) invalid(path)
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.length > MAX_SEGMENT_CHARACTERS }) {
            invalid(path)
        }
        return path
    }

    fun requireSupportedEntryPath(path: String): String {
        requireValidArchivePath(path)
        if (path !in fixedEntries && !isAssetPath(path)) {
            throw backupFailure(
                BackupFormatFailure.UNDECLARED_ENTRY,
                "The backup contains an unsupported entry path.",
            )
        }
        return path
    }

    fun requireStableId(value: String, fieldName: String): String {
        if (!stableId.matches(value)) {
            throw backupFailure(
                BackupFormatFailure.INVALID_METADATA,
                "$fieldName is not a portable stable ID.",
            )
        }
        return value
    }

    fun isAssetPath(path: String): Boolean = pageAsset.matches(path) || sourceAsset.matches(path)

    fun requirePageAssetPath(record: BackupPageRecord) {
        requireValidArchivePath(record.relativePath)
        val match = pageAsset.matchEntire(record.relativePath)
            ?: throw backupFailure(
                BackupFormatFailure.INVALID_PATH,
                "A page asset path does not match the v1 layout.",
            )
        val pathPosition = match.groupValues[2].toLongOrNull()
        if (
            match.groupValues[1] != record.documentId ||
            match.groupValues[3] != record.pageId ||
            pathPosition != record.position.toLong() ||
            match.groupValues[4] != extensionForPageMimeType(record.mimeType)
        ) {
            throw backupFailure(
                BackupFormatFailure.RELATIONSHIP_INVALID,
                "A page asset path does not match its metadata.",
            )
        }
    }

    fun requireSourceAssetPath(record: BackupSourceAssetRecord) {
        requireValidArchivePath(record.relativePath)
        val match = sourceAsset.matchEntire(record.relativePath)
            ?: throw backupFailure(
                BackupFormatFailure.INVALID_PATH,
                "A source asset path does not match the v1 layout.",
            )
        if (match.groupValues[1] != record.documentId || match.groupValues[2] != record.sourceId) {
            throw backupFailure(
                BackupFormatFailure.RELATIONSHIP_INVALID,
                "A source asset path does not match its metadata.",
            )
        }
    }

    fun canonicalCollisionKey(path: String): String {
        requireValidArchivePath(path)
        return Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun extensionForPageMimeType(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> throw backupFailure(
            BackupFormatFailure.INVALID_METADATA,
            "A page has an unsupported MIME type.",
        )
    }

    private fun invalid(@Suppress("UNUSED_PARAMETER") path: String): Nothing = throw backupFailure(
        BackupFormatFailure.INVALID_PATH,
        "The backup contains an invalid path.",
    )

}

internal class BackupPathRegistry {
    private val exactPaths = HashSet<String>()
    private val canonicalPaths = HashSet<String>()

    fun add(path: String) {
        BackupPathValidator.requireSupportedEntryPath(path)
        if (!exactPaths.add(path) || !canonicalPaths.add(BackupPathValidator.canonicalCollisionKey(path))) {
            throw backupFailure(
                BackupFormatFailure.DUPLICATE_ENTRY,
                "The backup contains duplicate or canonically colliding entry names.",
            )
        }
    }
}
