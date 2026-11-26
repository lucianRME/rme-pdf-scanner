package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test
class ShadowGlareBackupCompatibilityTest {
    @Test fun oldReaderRejectsEveryUnsupportedVersionedRecipeAndStillReadsOrdinaryData() {
        val f=BackupFormatTestFixture()
        M07BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,f.pages,f.sourceAssets)
        for(name in listOf("DOCUMENT_CLEANUP","SHADOW_GLARE_LOW_V1","SHADOW_GLARE_BALANCED_V1","SHADOW_GLARE_STRONG_V1")) {
            val pages=f.pages.map{it.copy(filterName=name)}
            BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,pages,f.sourceAssets)
            try{M07BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,pages,f.sourceAssets);fail()}catch(e:BackupFormatException){assertEquals(BackupFormatFailure.INVALID_METADATA,e.failure)}
        }
        assertEquals(8,RME_BACKUP_FORMAT_VERSION);assertEquals(8,RME_BACKUP_READER_VERSION)
    }
    @Test fun allVersionedRecipesRoundTripWithoutChangingSourceAssetsOrManualSettings() {
        val f=BackupFormatTestFixture()
        for(name in listOf("SHADOW_GLARE_LOW_V1","SHADOW_GLARE_BALANCED_V1","SHADOW_GLARE_STRONG_V1")) {
            val page=f.pages.first().copy(filterName=name,brightness=20,contrast=-25)
            assertEquals(page,BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(page),BackupFormatLimits()))
        }
    }
}
