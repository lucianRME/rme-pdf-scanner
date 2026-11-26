package org.synapseworks.pageharbor.backup.format

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.image.*

class CreaseStainCompatibilityTest {
    @Test fun actualM25ValidatorRejectsEachNewRecipeAndCurrentReaderRoundTripsIt() {
        val f=BackupFormatTestFixture()
        M25BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,f.pages,f.sourceAssets)
        for(name in DocumentFilter.entries.filter{it.creaseStainAmount!=null}.map{it.name}) {
            val pages=f.pages.map{it.copy(filterName=name)}
            BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,pages,f.sourceAssets)
            for(page in pages)assertEquals(page,BackupJsonCodec.decodePage(BackupJsonCodec.encodePage(page),BackupFormatLimits()))
            try{M25BackupFormatValidator.validateCompleteBackup(f.manifest,f.folders,f.documents,pages,f.sourceAssets);fail()}catch(e:BackupFormatException){assertEquals(BackupFormatFailure.INVALID_METADATA,e.failure)}
        }
        assertEquals(8,RME_BACKUP_FORMAT_VERSION);assertEquals(8,RME_BACKUP_READER_VERSION)
    }
}
