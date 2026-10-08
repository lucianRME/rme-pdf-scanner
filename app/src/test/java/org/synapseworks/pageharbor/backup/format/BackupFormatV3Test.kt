package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.synapseworks.pageharbor.backup.crypto.EncryptedBackupEnvelope

class BackupFormatV3Test {
    @Test
    fun v3RoundTripPreservesDatesProvenanceImportedTextAndCorrection() {
        val fixture = fixture()
        val output = ByteArrayOutputStream()

        val written = writeBackupArchiveForTest(
            destination = output,
            manifest = fixture.manifest,
            records = fixture.records,
            assets = fixture.assets,
        )
        val verified = readAndVerify(output.toByteArray())

        assertEquals(RME_BACKUP_FORMAT_VERSION_V3, written.manifest.formatVersion)
        assertEquals(RME_BACKUP_FORMAT_VERSION_V3, written.manifest.minimumReaderVersion)
        assertEquals(emptyList<String>(), written.manifest.requiredFeatures)
        assertEquals(fixture.document, verified.documents.single())
        val artifact = requireNotNull(verified.ocr).artifacts.single()
        assertEquals(BackupTextSourceKind.PDF_EMBEDDED_TEXT, artifact.textSourceKind)
        assertEquals("pdf-sha256:fixture:page:0:extractor:PDF_TEXT_LAYER_V1", artifact.sourceDescriptor)
        assertNull(artifact.actualScript)
        assertEquals(0, artifact.lineCount)
        assertEquals("corrected imported text", verified.ocr?.corrections?.single()?.correctedText)
    }

    @Test
    fun encryptedV3EnvelopeRestoresTheSameAuthenticatedInnerArchive() {
        val fixture = fixture()
        val plain = ByteArrayOutputStream().also { output ->
            writeBackupArchiveForTest(output, fixture.manifest, fixture.records, fixture.assets)
        }.toByteArray()
        val password = "synthetic-v3-password".toCharArray()
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

        assertEquals(RME_BACKUP_FORMAT_VERSION_V3, verified.manifest.formatVersion)
        assertEquals(1_700_000_000_000L, verified.documents.single().importedAtEpochMillis)
        assertEquals(BackupTextSourceKind.PDF_EMBEDDED_TEXT, verified.ocr?.artifacts?.single()?.textSourceKind)
    }

    @Test
    fun v1AndV2RecordsDecodeWithoutInventingV3Fields() {
        val fixture = BackupFormatTestFixture()
        val v1 = readAndVerify(fixture.writeArchive()).documents.single()
        assertNull(v1.documentDateEpochDay)
        assertNull(v1.originalCreatedAtEpochMillis)
        assertNull(v1.sourceModifiedAtEpochMillis)
        assertNull(v1.importedAtEpochMillis)
        assertNull(v1.sourceProvenanceKind)

        val documentJson = BackupJsonCodec.encodeDocument(fixture.documents.single())
        val decoded = BackupJsonCodec.decodeDocument(documentJson, BackupFormatLimits())
        assertNull(decoded.documentDateProvenance)
        assertNull(decoded.sourceModifiedAtProvenance)
        assertNull(decoded.sourceProvenanceLabel)
    }

    private fun fixture(): V3Fixture {
        val base = BackupFormatTestFixture()
        val page = base.pages.first().copy(ocrText = "embedded imported text")
        val pages = listOf(page) + base.pages.drop(1).map { it.copy(ocrText = null) }
        val document = base.documents.single().copy(
            documentDateEpochDay = 20_000,
            documentDateProvenance = "USER_CONFIRMED",
            documentDateConfidence = "EXACT",
            originalCreatedAtEpochMillis = 1_600_000_000_000L,
            originalCreatedAtProvenance = "FILE_METADATA",
            sourceModifiedAtEpochMillis = 1_650_000_000_000L,
            sourceModifiedAtProvenance = "FILE_METADATA",
            importedAtEpochMillis = 1_700_000_000_000L,
            sourceProvenanceKind = "GENERIC_PDF",
            sourceProvenanceLabel = "Imported PDF",
        )
        val artifact = BackupOcrArtifactRecord(
            pageId = page.pageId,
            artifactRevision = 1,
            capturedPageVisualRevision = 0,
            capturedDocumentContentRevision = 0,
            verificationState = BackupOcrVerificationState.CURRENT_VERIFIED,
            inputFingerprint = BackupOcrInputFingerprint(
                version = 1,
                value = "sha256:fixture-fingerprint",
                contentSha256 = page.sha256,
                visualRevision = 0,
                rotationDegrees = page.rotationDegrees,
                filterName = page.filterName,
                uprightWidth = page.width,
                uprightHeight = page.height,
                coordinateSystemVersion = 1,
                transformVersion = 1,
            ),
            actualScript = null,
            recognizerId = "PDF_TEXT_LAYER_V1",
            pipelineVersion = "PDF_TEXT_LAYER_V1",
            clientVersion = null,
            delivery = "LOCAL_PDFBOX",
            recognizedAtEpochMillis = 1_700_000_000_100L,
            rawText = requireNotNull(page.ocrText),
            lineCount = 0,
            textSourceKind = BackupTextSourceKind.PDF_EMBEDDED_TEXT,
            sourceDescriptor = "pdf-sha256:fixture:page:0:extractor:PDF_TEXT_LAYER_V1",
        )
        val records = SnapshotBackupRecordSource(
            folders = base.folders,
            documents = listOf(document),
            pages = pages,
            sourceAssets = base.sourceAssets,
            ocrDocumentStates = listOf(
                BackupOcrDocumentStateRecord(document.documentId, 0, "AUTOMATIC"),
            ),
            ocrPageStates = pages.map { candidate ->
                BackupOcrPageStateRecord(
                    pageId = candidate.pageId,
                    visualRevision = 0,
                    ocrStateRevision = if (candidate.pageId == page.pageId) 2 else 0,
                    activeArtifactRevision = if (candidate.pageId == page.pageId) 1 else null,
                )
            },
            ocrArtifacts = listOf(artifact),
            ocrCorrections = listOf(
                BackupOcrCorrectionRecord(
                    pageId = page.pageId,
                    baseArtifactRevision = 1,
                    correctedText = "corrected imported text",
                    correctedAtEpochMillis = 1_700_000_000_200L,
                    alignment = BackupOcrCorrectionAlignment.FREEFORM,
                    lineCorrections = emptyList(),
                ),
            ),
        )
        return V3Fixture(
            manifest = base.manifest.copy(
                formatVersion = RME_BACKUP_FORMAT_VERSION_V3,
                minimumReaderVersion = RME_BACKUP_FORMAT_VERSION_V3,
                producer = BackupProducer("org.synapseworks.pageharbor", "1.6.0", 18),
                ocr = BackupOcrManifest.empty(),
            ),
            records = records,
            assets = base.assets,
            document = document,
        )
    }

    private data class V3Fixture(
        val manifest: BackupManifest,
        val records: BackupRecordSource,
        val assets: BackupAssetStreamOpener,
        val document: BackupDocumentRecord,
    )
}
