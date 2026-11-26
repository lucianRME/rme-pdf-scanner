package org.synapseworks.pageharbor.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_7_8 = object : Migration(7,8) {
 override fun migrate(database:SupportSQLiteDatabase) {
  database.execSQL("ALTER TABLE library_pages ADD COLUMN paper_size TEXT NOT NULL DEFAULT ''")
 }
}
