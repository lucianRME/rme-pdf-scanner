package org.synapseworks.pageharbor.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_5_6 = object : Migration(5,6) {
 override fun migrate(database:SupportSQLiteDatabase) {
  database.execSQL("ALTER TABLE library_pages ADD COLUMN annotations TEXT NOT NULL DEFAULT ''")
 }
}

/** Same TEXT column; v7 guards the v2 grouped handwriting encoding against older app readers. */
internal val MIGRATION_6_7 = object : Migration(6,7) {
 override fun migrate(database:SupportSQLiteDatabase) = Unit
}
