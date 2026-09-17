package org.synapseworks.pageharbor.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LibraryFolderEntity::class,
        LibraryDocumentEntity::class,
        LibraryPageEntity::class,
        LibraryDocumentSearchEntity::class,
    ],
    version = 1,
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
            ).build().also { database -> instance = database }
        }
    }
}

const val LIBRARY_DATABASE_NAME = "rme-library.db"
