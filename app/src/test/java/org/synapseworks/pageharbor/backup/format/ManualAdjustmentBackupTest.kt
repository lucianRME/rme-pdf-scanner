package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test

class ManualAdjustmentBackupTest {
    private val page = BackupPageRecord("page", "document", 0, "documents/document/pages/page.jpg", "image/jpeg", "1".repeat(64), 3, 1, 1, 0, "ORIGINAL", null, null, null)
    @Test fun newParametersRoundTripExactlyAndLegacyMissingFieldsAreNeutral() {
        val changed = page.copy(brightness=-100,contrast=100)
        assertEquals(changed,BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(changed),BackupFormatLimits()))
        val legacy = BackupJsonCodec.encodePage(page).replace(",\"brightness\":0", "").replace(",\"contrast\":0", "")
        assertEquals(page,BackupJsonCodec.decodePage(legacy,BackupFormatLimits()))
    }
    @Test fun malformedParameterTypesAreRejectedRatherThanTruncated() {
        try {
            BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(page).replace("\"brightness\":0","\"brightness\":0.5"),BackupFormatLimits())
            fail()
        } catch (_: BackupFormatException) {}
    }
    @Test fun nonNeutralDataRequiresVersionFourAndAllParametersStayBounded() {
        val fixture=BackupFormatTestFixture()
        val modern=fixture.manifest.copy(formatVersion=4,minimumReaderVersion=4,ocr=BackupOcrManifest.empty())
        fun validate(manifest:BackupManifest,brightness:Int,contrast:Int)=BackupFormatValidator.validateCompleteBackup(
            manifest,fixture.folders,fixture.documents,fixture.pages.map{it.copy(brightness=brightness,contrast=contrast)},fixture.sourceAssets)
        validate(modern,-100,100)
        for((manifest,b,c) in listOf(Triple(modern,101,0),Triple(modern,0,-101),Triple(modern.copy(formatVersion=3,minimumReaderVersion=3),20,0))) {
            try {validate(manifest,b,c);fail()}catch(e:BackupFormatException){assertEquals(BackupFormatFailure.INVALID_METADATA,e.failure)}
        }
    }
    @Test fun writerRequiresCurrentReaderInsteadOfSilentlyDroppingVisualEdits() {
        assertEquals(8,RME_BACKUP_FORMAT_VERSION)
        assertEquals(8,RME_BACKUP_READER_VERSION)
    }
}
