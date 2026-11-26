package org.synapseworks.pageharbor.library.recycle

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_9_10=object:Migration(9,10){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE library_documents ADD COLUMN trashed_at INTEGER")
        db.execSQL("ALTER TABLE library_documents ADD COLUMN trash_revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_documents_library_state_trash_revision_document_id ON library_documents(library_state,trash_revision,document_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS library_deleted_identities(document_id TEXT NOT NULL,source_document_id TEXT NOT NULL,PRIMARY KEY(document_id,source_document_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_deleted_identities_source_document_id ON library_deleted_identities(source_document_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS library_document_origins(document_id TEXT NOT NULL,source_document_id TEXT NOT NULL,PRIMARY KEY(document_id,source_document_id),FOREIGN KEY(document_id) REFERENCES library_documents(document_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_document_origins_source_document_id ON library_document_origins(source_document_id)")
    }
}
