package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        org.synapseworks.pageharbor.library.recycle.DeletedDocumentIdentity::class,
        org.synapseworks.pageharbor.library.recycle.DocumentArchiveOrigin::class,
        org.synapseworks.pageharbor.library.organization.LibraryTagEntity::class,
        org.synapseworks.pageharbor.library.organization.LibraryDocumentTagEntity::class,
        org.synapseworks.pageharbor.library.organization.LibraryPendingRestoreTagEntity::class,
        LibraryFolderEntity::class,
        LibraryMetadataEntity::class,
        LibraryDataOperationEntity::class,
        LibraryPendingRestoreFolderEntity::class,
        LibraryDocumentEntity::class,
        LibraryPageEntity::class,
        LibrarySourceAssetEntity::class,
        LibraryDataOperationItemEntity::class,
        LibraryDataOperationSourceEntity::class,
        LibraryPageOcrArtifactEntity::class,
        LibraryPageOcrLineEntity::class,
        LibraryPageOcrCorrectionEntity::class,
        LibraryPageOcrCorrectionLineEntity::class,
        LibraryDocumentSearchContentEntity::class,
        LibraryDocumentSearchEntity::class,
        LibraryPageSearchContentEntity::class,
        LibraryPageSearchEntity::class,
        OcrBatchJobEntity::class,
        OcrBatchItemEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                LIBRARY_DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, org.synapseworks.pageharbor.library.organization.MIGRATION_8_9, org.synapseworks.pageharbor.library.recycle.MIGRATION_9_10)
                .build()
                .also { database -> instance = database }
        }
    }
}

const val LIBRARY_DATABASE_NAME = "rme-library.db"
