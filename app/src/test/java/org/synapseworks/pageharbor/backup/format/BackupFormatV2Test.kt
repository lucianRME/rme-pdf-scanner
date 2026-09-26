package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.crypto.EncryptedBackupEnvelope

class BackupFormatV2Test {
    @Test
    fun v1ArchiveRemainsReadableWithoutInventingV2State() {
        val verified = readAndVerify(BackupFormatTestFixture().writeArchive())

        assertEquals(RME_BACKUP_FORMAT_VERSION_V1, verified.manifest.formatVersion)
        assertNull(verified.ocr)
        assertEquals("Synthetic text", verified.pages.first().ocrText)
    }

    @Test
    fun v2RoundTripPreservesRawUnicodeEmptyCorrectionScriptFingerprintAndGeometry() {
        val fixture = BackupFormatTestFixture()
        val v2 = singleArtifactV2(fixture)
        val output = ByteArrayOutputStream()

        val written = writeBackupArchiveForTest(
            destination = output,
            manifest = v2.manifest,
            records = v2.records,
            assets = fixture.assets,
        )
        val staging = MemoryStagingSink()
        val verified = readAndVerify(output.toByteArray(), staging)
        val ocr = requireNotNull(verified.ocr)

        assertEquals(RME_BACKUP_FORMAT_VERSION_V2, written.manifest.formatVersion)
        assertEquals(RME_BACKUP_READER_VERSION, written.manifest.minimumReaderVersion)
        assertEquals("AUTOMATIC", ocr.documentStates.single().scriptPreference)
        assertEquals("देवनागरी — 日本語 — Latin", ocr.artifacts.single().rawText)
        assertEquals("LATIN", ocr.artifacts.single().actualScript)
        assertEquals(7, ocr.artifacts.single().inputFingerprint?.version)
        assertEquals("input-fingerprint-v7", ocr.artifacts.single().inputFingerprint?.value)
        assertEquals("client-17", ocr.artifacts.single().clientVersion)
        assertEquals("", ocr.corrections.single().correctedText)
        assertEquals("", ocr.corrections.single().lineCorrections.single().correctedText)
        assertEquals(v2.lines, ocr.lines.records().toList())
        assertEquals(1, ocr.lines.recordCount)
        assertTrue(staging.verified)
        assertFalse(staging.aborted)
    }

    @Test
    fun v1WriterRejectsV2OnlyRecordsInsteadOfFlatteningThem() {
        val fixture = BackupFormatTestFixture()
        val v2 = singleArtifactV2(fixture)

        assertBackupFailure(BackupFormatFailure.UNSUPPORTED_VERSION) {
            writeBackupArchiveForTest(
                destination = ByteArrayOutputStream(),
                manifest = fixture.manifest,
                records = v2.records,
                assets = fixture.assets,
            )
        }
    }

    @Test
    fun v2WriterRejectsUnsupportedScriptAndMoreThanFiveThousandPositionedLines() {
        val fixture = BackupFormatTestFixture()
        val unsupportedScript = singleArtifactV2(fixture, actualScript = "ARABIC")
        assertBackupFailure(BackupFormatFailure.INVALID_METADATA) {
            writeBackupArchiveForTest(
                ByteArrayOutputStream(),
                unsupportedScript.manifest,
                unsupportedScript.records,
                fixture.assets,
            )
        }

        val excessiveLayout = singleArtifactV2(fixture, declaredLineCount = 5_001)
        assertBackupFailure(BackupFormatFailure.INVALID_METADATA) {
            writeBackupArchiveForTest(
                ByteArrayOutputStream(),
                excessiveLayout.manifest,
                excessiveLayout.records,
                fixture.assets,
            )
        }
    }

    @Test
    fun zeroOcrV2StillWritesAllFourRequiredMetadataEntries() {
        val fixture = BackupFormatTestFixture()
        val pages = fixture.pages.map { it.copy(ocrText = null, ocrError = null) }
        val records = SnapshotBackupRecordSource(
            folders = fixture.folders,
            documents = fixture.documents,
            pages = pages,
            sourceAssets = fixture.sourceAssets,
            ocrDocumentStates = fixture.documents.map { document ->
                BackupOcrDocumentStateRecord(document.documentId, 0, null)
            },
            ocrPageStates = pages.map { page ->
                BackupOcrPageStateRecord(page.pageId, 0, 0, null)
            },
        )
        val manifest = fixture.manifest.copy(
            formatVersion = RME_BACKUP_FORMAT_VERSION_V2,
            minimumReaderVersion = RME_BACKUP_READER_VERSION,
            ocr = BackupOcrManifest.empty(),
        )
        val output = ByteArrayOutputStream()

        writeBackupArchiveForTest(output, manifest, records, fixture.assets)
        val entries = archiveEntries(output.toByteArray())
        val verified = readAndVerify(output.toByteArray())

        assertTrue(RME_BACKUP_OCR_DOCUMENT_STATES_PATH in entries)
        assertTrue(RME_BACKUP_OCR_PAGE_STATES_PATH in entries)
        assertTrue(RME_BACKUP_OCR_ARTIFACTS_PATH in entries)
        assertTrue(RME_BACKUP_OCR_CORRECTIONS_PATH in entries)
        assertNotNull(verified.ocr)
        assertEquals(0, verified.ocr?.lines?.recordCount)
    }

    @Test
    fun unchangedEncryptedEnvelopeWrapsAndRestoresV2InnerZip() {
        val fixture = BackupFormatTestFixture()
        val v2 = singleArtifactV2(fixture)
        val plain = ByteArrayOutputStream().also { output ->
            writeBackupArchiveForTest(output, v2.manifest, v2.records, fixture.assets)
        }.toByteArray()
        val password = "synthetic-v2-password".toCharArray()
        val encrypted = ByteArrayOutputStream()
        val decrypted = ByteArrayOutputStream()

        EncryptedBackupEnvelope.encrypt(ByteArrayInputStream(plain), encrypted, password)
        EncryptedBackupEnvelope.decrypt(
            ByteArrayInputStream(encrypted.toByteArray()),
            decrypted,
            password,
        )
        password.fill('\u0000')
        val verified = readAndVerify(decrypted.toByteArray())

        assertEquals(RME_BACKUP_FORMAT_VERSION_V2, verified.manifest.formatVersion)
        assertEquals("", verified.ocr?.corrections?.single()?.correctedText)
    }

    @Test
    fun tenThousandPageV2WriterKeepsLineMetadataInBoundedChunks() {
        val pageCount = 10_000
        val pageBytes = syntheticPng()
        val pageSha = sha256(pageBytes)
        val records = largeRecordSource(pageCount, pageBytes.size.toLong(), pageSha)
        val manifest = BackupManifest(
            formatVersion = RME_BACKUP_FORMAT_VERSION_V2,
            minimumReaderVersion = RME_BACKUP_READER_VERSION,
            requiredFeatures = emptyList(),
            backupId = "00000000-0000-4000-8000-000000000016",
            createdAtEpochMillis = 8_000L,
            producer = BackupProducer("org.synapseworks.pageharbor", "1.6.0", 17),
            summary = BackupSummary(0, 1, pageCount, 0, pageBytes.size.toLong() * pageCount),
            metadata = BackupMetadataPaths(
                RME_BACKUP_FOLDERS_PATH,
                RME_BACKUP_DOCUMENTS_PATH,
                RME_BACKUP_PAGES_PATH,
                RME_BACKUP_SOURCE_ASSETS_PATH,
            ),
            integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
            ocr = BackupOcrManifest.empty(),
        )
        val limits = BackupFormatLimits(
            maximumOcrLineChunkRecords = 128,
            maximumOcrLineChunkBytes = 64 * 1024,
        )
        val output = ByteArrayOutputStream()

        val result = writeBackupArchiveForTest(
            destination = output,
            manifest = manifest,
            records = records,
            assets = BackupAssetStreamOpener { ByteArrayInputStream(pageBytes) },
            limits = limits,
        )
        val chunks = requireNotNull(result.manifest.ocr).lineChunks

        assertTrue(chunks.size > 1)
        assertEquals(pageCount, chunks.sumOf(BackupOcrLineChunkDescriptor::recordCount))
        assertTrue(chunks.all { it.recordCount <= 128 })
        assertTrue(chunks.all { it.byteLength <= 64 * 1024 })
        val observedChunkBytes = linkedMapOf<String, Long>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (BackupPathValidator.isOcrLineChunkPath(entry.name)) {
                    var bytes = 0L
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        bytes += count
                    }
                    observedChunkBytes[entry.name] = bytes
                }
            }
        }
        assertEquals(chunks.associate { it.path to it.byteLength }, observedChunkBytes)
    }

    private fun singleArtifactV2(
        fixture: BackupFormatTestFixture,
        actualScript: String = "LATIN",
        declaredLineCount: Int = 1,
    ): V2Fixture {
        val page = fixture.pages.first()
        val line = BackupOcrLineRecord(
            pageId = page.pageId,
            artifactRevision = 3,
            lineOrdinal = 0,
            rawText = "देवनागरी — 日本語 — Latin",
            cornerPoints = listOf(
                BackupOcrPoint(0.1, 0.2),
                BackupOcrPoint(0.9, 0.2),
                BackupOcrPoint(0.9, 0.3),
                BackupOcrPoint(0.1, 0.3),
            ),
            baselineStart = BackupOcrPoint(0.1, 0.28),
            baselineEnd = BackupOcrPoint(0.9, 0.28),
            baselineAngleDegrees = 0.0,
            writingOrientation = "HORIZONTAL_LTR",
        )
        val artifact = BackupOcrArtifactRecord(
            pageId = page.pageId,
            artifactRevision = 3,
            capturedPageVisualRevision = 4,
            capturedDocumentContentRevision = 9,
            verificationState = BackupOcrVerificationState.CURRENT_VERIFIED,
            inputFingerprint = BackupOcrInputFingerprint(
                version = 7,
                value = "input-fingerprint-v7",
                contentSha256 = page.sha256,
                visualRevision = 4,
                rotationDegrees = page.rotationDegrees,
                filterName = page.filterName,
                uprightWidth = page.width,
                uprightHeight = page.height,
                coordinateSystemVersion = 1,
                transformVersion = 2,
            ),
            actualScript = actualScript,
            recognizerId = "mlkit-latin-v1",
            pipelineVersion = "pipeline-2",
            clientVersion = "client-17",
            delivery = "BUNDLED",
            recognizedAtEpochMillis = 6_000L,
            rawText = line.rawText,
            lineCount = declaredLineCount,
        )
        val pages = fixture.pages.map { candidate ->
            candidate.copy(ocrText = if (candidate.pageId == page.pageId) artifact.rawText else null)
        }
        val records = SnapshotBackupRecordSource(
            folders = fixture.folders,
            documents = fixture.documents,
            pages = pages,
            sourceAssets = fixture.sourceAssets,
            ocrDocumentStates = listOf(BackupOcrDocumentStateRecord("document-1", 9, "AUTOMATIC")),
            ocrPageStates = pages.map { candidate ->
                if (candidate.pageId == page.pageId) {
                    BackupOcrPageStateRecord(candidate.pageId, 4, 5, 3)
                } else {
                    BackupOcrPageStateRecord(candidate.pageId, 0, 0, null)
                }
            },
            ocrArtifacts = listOf(artifact),
            ocrCorrections = listOf(
                BackupOcrCorrectionRecord(
                    pageId = page.pageId,
                    baseArtifactRevision = 3,
                    correctedText = "",
                    correctedAtEpochMillis = 7_000L,
                    alignment = BackupOcrCorrectionAlignment.LINE_ALIGNED,
                    lineCorrections = listOf(BackupOcrLineCorrectionRecord(0, "")),
                ),
            ),
            ocrLines = listOf(line),
        )
        return V2Fixture(
            manifest = fixture.manifest.copy(
                formatVersion = RME_BACKUP_FORMAT_VERSION_V2,
                minimumReaderVersion = RME_BACKUP_READER_VERSION,
                producer = BackupProducer("org.synapseworks.pageharbor", "1.6.0", 17),
                ocr = BackupOcrManifest.empty(),
            ),
            records = records,
            lines = listOf(line),
        )
    }

    private fun largeRecordSource(
        pageCount: Int,
        pageByteLength: Long,
        pageSha: String,
    ): BackupRecordSource = object : BackupRecordSource {
        override fun folders(): Sequence<BackupFolderRecord> = emptySequence()

        override fun documents(): Sequence<BackupDocumentRecord> = sequenceOf(
            BackupDocumentRecord(
                documentId = "document-large",
                folderId = null,
                title = "Synthetic large backup",
                createdAtEpochMillis = 1_000L,
                modifiedAtEpochMillis = 2_000L,
                contentHashVersion = 1,
                contentSha256 = null,
                pageCount = pageCount,
                sourceAssetCount = 0,
            ),
        )

        override fun pages(): Sequence<BackupPageRecord> = (0 until pageCount).asSequence().map { index ->
            val pageId = "page-${index.toString().padStart(5, '0')}"
            BackupPageRecord(
                pageId = pageId,
                documentId = "document-large",
                position = index,
                relativePath = "documents/document-large/pages/$index-$pageId.png",
                mimeType = "image/png",
                sha256 = pageSha,
                byteLength = pageByteLength,
                width = 1,
                height = 1,
                rotationDegrees = 0,
                filterName = "ORIGINAL",
                ocrText = "Synthetic OCR $pageId",
                ocrError = null,
                sourcePageIndex = null,
            )
        }

        override fun sourceAssets(): Sequence<BackupSourceAssetRecord> = emptySequence()

        override fun ocrDocumentStates(): Sequence<BackupOcrDocumentStateRecord> =
            sequenceOf(BackupOcrDocumentStateRecord("document-large", 1, "LATIN"))

        override fun ocrPageStates(): Sequence<BackupOcrPageStateRecord> =
            (0 until pageCount).asSequence().map { index ->
                BackupOcrPageStateRecord("page-${index.toString().padStart(5, '0')}", 0, 1, 1)
            }

        override fun ocrArtifacts(): Sequence<BackupOcrArtifactRecord> =
            (0 until pageCount).asSequence().map { index ->
                val pageId = "page-${index.toString().padStart(5, '0')}"
                BackupOcrArtifactRecord(
                    pageId = pageId,
                    artifactRevision = 1,
                    capturedPageVisualRevision = 0,
                    capturedDocumentContentRevision = 1,
                    verificationState = BackupOcrVerificationState.CURRENT_VERIFIED,
                    inputFingerprint = BackupOcrInputFingerprint(
                        version = 1,
                        value = "fingerprint-$pageId",
                        contentSha256 = pageSha,
                        visualRevision = 0,
                        rotationDegrees = 0,
                        filterName = "ORIGINAL",
                        uprightWidth = 1,
                        uprightHeight = 1,
                        coordinateSystemVersion = 1,
                        transformVersion = 1,
                    ),
                    actualScript = "LATIN",
                    recognizerId = "synthetic-latin",
                    pipelineVersion = "1",
                    clientVersion = "17",
                    delivery = "BUNDLED",
                    recognizedAtEpochMillis = 3_000L,
                    rawText = "Synthetic OCR $pageId",
                    lineCount = 1,
                )
            }

        override fun ocrCorrections(): Sequence<BackupOcrCorrectionRecord> = emptySequence()

        override fun ocrLines(): Sequence<BackupOcrLineRecord> =
            (0 until pageCount).asSequence().map { index ->
                val pageId = "page-${index.toString().padStart(5, '0')}"
                BackupOcrLineRecord(
                    pageId = pageId,
                    artifactRevision = 1,
                    lineOrdinal = 0,
                    rawText = "Synthetic OCR $pageId",
                    cornerPoints = listOf(
                        BackupOcrPoint(0.1, 0.1),
                        BackupOcrPoint(0.9, 0.1),
                        BackupOcrPoint(0.9, 0.2),
                        BackupOcrPoint(0.1, 0.2),
                    ),
                    baselineStart = BackupOcrPoint(0.1, 0.18),
                    baselineEnd = BackupOcrPoint(0.9, 0.18),
                    baselineAngleDegrees = 0.0,
                    writingOrientation = "HORIZONTAL_LTR",
                )
            }
    }

    private fun syntheticPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00,
    )

    private data class V2Fixture(
        val manifest: BackupManifest,
        val records: BackupRecordSource,
        val lines: List<BackupOcrLineRecord>,
    )
}
