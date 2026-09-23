package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveRejectionTest {
    @Test
    fun traversalEntryIsRejectedBeforeItCanReachStaging() {
        val staging = MemoryStagingSink()
        val archive = rawZip(mapOf("../outside" to byteArrayOf(1)))

        assertBackupFailure(BackupFormatFailure.INVALID_PATH) {
            readAndVerify(archive, staging)
        }

        assertTrue(staging.aborted)
        assertTrue(staging.assets.isEmpty())
    }

    @Test
    fun undeclaredAndMissingAssetsAreRejected() {
        val fixture = BackupFormatTestFixture()
        val original = archiveEntries(fixture.writeArchive())
        val extraPath = "documents/document-1/pages/99-orphan.png"
        val extraBytes = fixture.pageBytesByPath.values.first()
        val withExtra = archiveWithFreshLedger(original + mapOf(extraPath to extraBytes))
        assertBackupFailure(BackupFormatFailure.UNDECLARED_ENTRY) {
            readAndVerify(withExtra)
        }

        val withoutDeclared = LinkedHashMap(original).apply {
            remove(fixture.pages.first().relativePath)
        }
        assertBackupFailure(BackupFormatFailure.MISSING_ENTRY) {
            readAndVerify(archiveWithFreshLedger(withoutDeclared))
        }
    }

    @Test
    fun metadataSizeAndHashMustMatchStreamedAsset() {
        val fixture = BackupFormatTestFixture()
        val original = archiveEntries(fixture.writeArchive())
        val pagePath = fixture.pages.first().relativePath
        val actualSize = fixture.pages.first().byteLength
        val actualTotal = fixture.manifest.summary.contentByteLength

        val sizeMismatch = LinkedHashMap(original).apply {
            this[RME_BACKUP_PAGES_PATH] = getValue(RME_BACKUP_PAGES_PATH).utf8()
                .replaceFirst("\"byteLength\":$actualSize", "\"byteLength\":${actualSize + 1}")
                .toByteArray()
            this[RME_BACKUP_MANIFEST_PATH] = getValue(RME_BACKUP_MANIFEST_PATH).utf8()
                .replaceFirst(
                    "\"contentByteLength\":$actualTotal",
                    "\"contentByteLength\":${actualTotal + 1}",
                )
                .toByteArray()
        }
        assertBackupFailure(BackupFormatFailure.SIZE_MISMATCH) {
            readAndVerify(archiveWithFreshLedger(sizeMismatch))
        }

        val hashMismatch = LinkedHashMap(original).apply {
            this[RME_BACKUP_PAGES_PATH] = getValue(RME_BACKUP_PAGES_PATH).utf8()
                .replaceFirst(fixture.pages.first().sha256, "0".repeat(64))
                .toByteArray()
        }
        assertBackupFailure(BackupFormatFailure.HASH_MISMATCH) {
            readAndVerify(archiveWithFreshLedger(hashMismatch))
        }

        val changedAsset = LinkedHashMap(original).apply {
            this[pagePath] = getValue(pagePath).copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
        }
        assertBackupFailure(BackupFormatFailure.HASH_MISMATCH) {
            readAndVerify(rawZip(changedAsset))
        }

        val dimensionMismatch = LinkedHashMap(original).apply {
            this[RME_BACKUP_PAGES_PATH] = getValue(RME_BACKUP_PAGES_PATH).utf8()
                .replaceFirst("\"width\":1", "\"width\":2")
                .toByteArray()
        }
        assertBackupFailure(BackupFormatFailure.DIMENSION_MISMATCH) {
            readAndVerify(archiveWithFreshLedger(dimensionMismatch))
        }
    }

    @Test
    fun invalidRelationshipsAbortTheWholeStagingOperation() {
        val fixture = BackupFormatTestFixture()
        val entries = archiveEntries(fixture.writeArchive())
        entries[RME_BACKUP_FOLDERS_PATH] = entries.getValue(RME_BACKUP_FOLDERS_PATH).utf8()
            .replaceFirst("\"parentFolderId\":null", "\"parentFolderId\":\"missing-folder\"")
            .toByteArray()
        val staging = MemoryStagingSink()

        assertBackupFailure(BackupFormatFailure.RELATIONSHIP_INVALID) {
            readAndVerify(archiveWithFreshLedger(entries), staging)
        }

        assertTrue(staging.aborted)
        assertFalse(staging.verified)
        assertTrue(staging.assets.isEmpty())
    }

    @Test
    fun futureFormatAndUnknownRequiredFeatureFailCompatibility() {
        val fixture = BackupFormatTestFixture()
        val original = archiveEntries(fixture.writeArchive())
        val futureVersion = LinkedHashMap(original).apply {
            this[RME_BACKUP_MANIFEST_PATH] = getValue(RME_BACKUP_MANIFEST_PATH).utf8()
                .replaceFirst("\"formatVersion\":1", "\"formatVersion\":2")
                .toByteArray()
        }
        assertBackupFailure(BackupFormatFailure.UNSUPPORTED_VERSION) {
            readAndVerify(archiveWithFreshLedger(futureVersion))
        }

        val unknownFeature = LinkedHashMap(original).apply {
            this[RME_BACKUP_MANIFEST_PATH] = getValue(RME_BACKUP_MANIFEST_PATH).utf8()
                .replaceFirst("\"requiredFeatures\":[]", "\"requiredFeatures\":[\"future.feature\"]")
                .toByteArray()
        }
        assertBackupFailure(BackupFormatFailure.UNSUPPORTED_REQUIRED_FEATURE) {
            readAndVerify(archiveWithFreshLedger(unknownFeature))
        }
    }

    @Test
    fun decompressionBudgetStopsArchiveBeforeUnboundedExpansion() {
        val archive = BackupFormatTestFixture().writeArchive()
        val limits = BackupFormatLimits(maximumTotalUncompressedBytes = 64)

        assertBackupFailure(BackupFormatFailure.LIMIT_EXCEEDED) {
            BackupArchiveReader.readAndVerify(
                ByteArrayInputStream(archive),
                MemoryStagingSink(),
                limits = limits,
            )
        }
    }

    private fun ByteArray.utf8(): String = toString(Charsets.UTF_8)
}
