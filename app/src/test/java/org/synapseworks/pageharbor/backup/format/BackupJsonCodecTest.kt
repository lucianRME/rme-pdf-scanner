package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    @Test
    fun checkedInGoldenMetadataDefinesNestedTwentyOnePagePortableBackup() {
        val root = "/backup-format-v1/"
        val manifest = BackupJsonCodec.readManifest(requireNotNull(javaClass.getResourceAsStream(root + "manifest.json")))
        val folders = mutableListOf<BackupFolderRecord>()
        val documents = mutableListOf<BackupDocumentRecord>()
        val pages = mutableListOf<BackupPageRecord>()
        val sources = mutableListOf<BackupSourceAssetRecord>()
        BackupJsonCodec.readFolders(requireNotNull(javaClass.getResourceAsStream(root + "folders.jsonl"))) {
            folders += it
        }
        BackupJsonCodec.readDocuments(requireNotNull(javaClass.getResourceAsStream(root + "documents.jsonl"))) {
            documents += it
        }
        BackupJsonCodec.readPages(requireNotNull(javaClass.getResourceAsStream(root + "pages.jsonl"))) {
            pages += it
        }
        BackupJsonCodec.readSourceAssets(
            requireNotNull(javaClass.getResourceAsStream(root + "source-assets.jsonl")),
        ) { sources += it }

        BackupFormatValidator.validateCompleteBackup(manifest, folders, documents, pages, sources)

        assertEquals(2, folders.size)
        assertEquals("10000000-0000-4000-8000-000000000001", folders[1].parentFolderId)
        assertEquals(21, pages.size)
        assertEquals(1, sources.size)
        assertTrue(sources.single().relativePath.endsWith("/sources/40000000-0000-4000-8000-000000000001.pdf"))
    }

    @Test
    fun manifestRoundTripPreservesRequiredContractFieldsAndIgnoresOptionalFields() {
        val fixture = BackupFormatTestFixture(pageCount = 21)
        val output = ByteArrayOutputStream()
        BackupJsonCodec.writeManifest(fixture.manifest, output)
        val withUnknownOptionalField = output.toString(Charsets.UTF_8.name())
            .dropLast(1) + ",\"futureHint\":{\"value\":true}}"

        val decoded = BackupJsonCodec.readManifest(
            ByteArrayInputStream(withUnknownOptionalField.toByteArray()),
        )

        assertEquals(1, decoded.formatVersion)
        assertEquals(1, decoded.minimumReaderVersion)
        assertTrue(decoded.requiredFeatures.isEmpty())
        assertEquals(21, decoded.summary.pageCount)
        assertEquals(RME_BACKUP_PAGES_PATH, decoded.metadata.pages)
        assertEquals(RME_BACKUP_CHECKSUMS_PATH, decoded.integrity.checksumsEntry)
    }

    @Test
    fun duplicateKeysAndNonIntegralSchemaNumbersAreRejected() {
        val fixture = BackupFormatTestFixture()
        val output = ByteArrayOutputStream()
        BackupJsonCodec.writeManifest(fixture.manifest, output)
        val valid = output.toString(Charsets.UTF_8.name())

        assertBackupFailure(BackupFormatFailure.INVALID_JSON) {
            BackupJsonCodec.readManifest(
                ByteArrayInputStream(valid.replaceFirst("{", "{\"formatVersion\":1,").toByteArray()),
            )
        }
        assertBackupFailure(BackupFormatFailure.INVALID_JSON) {
            BackupJsonCodec.readManifest(
                ByteArrayInputStream(valid.replace("\"formatVersion\":1", "\"formatVersion\":1.0").toByteArray()),
            )
        }
    }

    @Test
    fun jsonlIsProcessedRecordByRecordAndEnforcesLineLimit() {
        val fixture = BackupFormatTestFixture(pageCount = 21)
        val output = ByteArrayOutputStream()
        assertEquals(21, BackupJsonCodec.writePages(fixture.pages.asSequence(), output))
        var count = 0
        BackupJsonCodec.readPages(ByteArrayInputStream(output.toByteArray())) { count += 1 }
        assertEquals(21, count)

        val tightLimits = BackupFormatLimits(maximumJsonLineBytes = 32)
        assertBackupFailure(BackupFormatFailure.LIMIT_EXCEEDED) {
            BackupJsonCodec.readPages(ByteArrayInputStream(output.toByteArray()), tightLimits) { }
        }
    }

    @Test
    fun checksumLedgerRequiresCanonicalSortedCompleteSyntax() {
        val valid = listOf(
            BackupChecksum("a".repeat(64), 10, RME_BACKUP_MANIFEST_PATH),
            BackupChecksum("b".repeat(64), 20, RME_BACKUP_PAGES_PATH),
        )
        val output = ByteArrayOutputStream()
        BackupChecksumLedger.write(valid.reversed(), output)
        val decoded = BackupChecksumLedger.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(valid, decoded)

        val unsorted = valid.reversed().joinToString(separator = "") {
            "${it.sha256}  ${it.byteLength}  ${it.path}\n"
        }
        assertBackupFailure(BackupFormatFailure.INVALID_LEDGER) {
            BackupChecksumLedger.read(ByteArrayInputStream(unsorted.toByteArray()))
        }
    }
}
