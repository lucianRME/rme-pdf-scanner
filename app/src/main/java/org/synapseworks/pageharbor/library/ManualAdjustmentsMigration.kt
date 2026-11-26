package org.synapseworks.pageharbor.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE library_pages ADD COLUMN brightness INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE library_pages ADD COLUMN contrast INTEGER NOT NULL DEFAULT 0")
    }
}
