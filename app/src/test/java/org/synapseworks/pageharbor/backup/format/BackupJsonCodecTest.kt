package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        writeChecksumLedgerForTest(valid.reversed(), output)
        val decoded = buildList {
            BackupChecksumLedger.read(ByteArrayInputStream(output.toByteArray()), accept = ::add)
        }
        assertEquals(valid, decoded)

        val unsorted = valid.reversed().joinToString(separator = "") {
            "${it.sha256}  ${it.byteLength}  ${it.path}\n"
        }
        assertBackupFailure(BackupFormatFailure.INVALID_LEDGER) {
            BackupChecksumLedger.read(ByteArrayInputStream(unsorted.toByteArray())) { }
        }
    }

    @Test
    fun checksumLedgerStreamsRecordsAndRejectsCanonicalPathCollisions() {
        val valid = listOf(
            BackupChecksum("a".repeat(64), 10, RME_BACKUP_MANIFEST_PATH),
            BackupChecksum("b".repeat(64), 20, RME_BACKUP_PAGES_PATH),
        )
        val output = ByteArrayOutputStream()
        writeChecksumLedgerForTest(valid, output)
        val streamed = mutableListOf<BackupChecksum>()

        val count = BackupChecksumLedger.read(
            ByteArrayInputStream(output.toByteArray()),
            accept = streamed::add,
        )

        assertEquals(valid.size, count)
        assertEquals(valid, streamed)

        val collision = listOf(
            BackupChecksum(
                "c".repeat(64),
                1,
                "documents/Doc/pages/000000-page.png",
            ),
            BackupChecksum(
                "d".repeat(64),
                1,
                "documents/doc/pages/000000-page.png",
            ),
        )
        assertBackupFailure(BackupFormatFailure.DUPLICATE_ENTRY) {
            writeChecksumLedgerForTest(collision, ByteArrayOutputStream())
        }

        val duplicate = buildString {
            repeat(2) { append("${"e".repeat(64)}  1  $RME_BACKUP_MANIFEST_PATH\n") }
        }
        assertBackupFailure(BackupFormatFailure.INVALID_LEDGER) {
            BackupChecksumLedger.read(ByteArrayInputStream(duplicate.toByteArray())) { }
        }
        assertBackupFailure(BackupFormatFailure.INVALID_LEDGER) {
            BackupChecksumLedger.read(ByteArrayInputStream("not-a-ledger-record\n".toByteArray())) { }
        }
    }

    @Test
    fun checksumExternalSortPropagatesCancellationAndDeletesItsSpool() {
        val root = Files.createTempDirectory("rme-checksum-cancellation").toFile()
        var cancelled = false
        val spool = BackupChecksumSpool(
            limits = BackupFormatLimits(),
            cancellationCheck = {
                if (cancelled) throw CancellationException("synthetic cancellation")
            },
            root = root,
        )
        spool.append(BackupChecksum("a".repeat(64), 1, RME_BACKUP_MANIFEST_PATH))
        cancelled = true

        try {
            assertThrows(CancellationException::class.java) {
                spool.writeLedger(ByteArrayOutputStream())
            }
        } finally {
            spool.close()
        }

        assertFalse(root.exists())
    }

    @Test
    fun checksumSpoolCleanupFailureIsObservableAndCloseCanRetry() {
        val root = Files.createTempDirectory("rme-checksum-cleanup-retry").toFile()
        var allowCleanup = false
        var cleanupAttempts = 0
        val spool = BackupChecksumSpool(
            limits = BackupFormatLimits(),
            root = root,
            cleanup = { directory ->
                cleanupAttempts += 1
                allowCleanup && directory.deleteRecursively()
            },
        )
        spool.append(BackupChecksum("a".repeat(64), 1, RME_BACKUP_MANIFEST_PATH))

        assertThrows(java.io.IOException::class.java, spool::close)
        assertEquals(3, cleanupAttempts)
        assertTrue(root.exists())

        allowCleanup = true
        spool.close()

        assertEquals(4, cleanupAttempts)
        assertFalse(root.exists())
    }

    @Test
    fun checksumExternalSortStreamsAcrossMultipleRunsInPathOrder() {
        val records = (0 until 4_100).map { index ->
            BackupChecksum(
                sha256 = index.toString(16).padStart(64, '0'),
                byteLength = index.toLong(),
                path = "documents/d$index/pages/0-p$index.png",
            )
        }
        val output = ByteArrayOutputStream()

        writeChecksumLedgerForTest(records.reversed(), output)

        var previousPath: String? = null
        val count = BackupChecksumLedger.read(ByteArrayInputStream(output.toByteArray())) { checksum ->
            previousPath?.let { previous -> assertTrue(previous < checksum.path) }
            previousPath = checksum.path
        }
        assertEquals(records.size, count)
    }
}

private fun writeChecksumLedgerForTest(
    records: Collection<BackupChecksum>,
    destination: ByteArrayOutputStream,
) {
    val scratch = Files.createTempDirectory("rme-checksum-ledger-test").toFile()
    try {
        BackupChecksumLedger.write(records, destination, scratch)
    } finally {
        scratch.deleteRecursively()
    }
}
