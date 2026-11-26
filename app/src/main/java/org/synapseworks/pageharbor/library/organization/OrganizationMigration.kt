package org.synapseworks.pageharbor.library.organization

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_8_9=object:Migration(8,9){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE library_documents ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_documents_library_state_is_favorite_modified_at_document_id ON library_documents(library_state,is_favorite,modified_at,document_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS library_tags(tag_id TEXT NOT NULL,name TEXT NOT NULL,normalized_name TEXT NOT NULL,PRIMARY KEY(tag_id))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_library_tags_normalized_name ON library_tags(normalized_name)")
        db.execSQL("CREATE TABLE IF NOT EXISTS library_document_tags(document_id TEXT NOT NULL,tag_id TEXT NOT NULL,PRIMARY KEY(document_id,tag_id),FOREIGN KEY(document_id) REFERENCES library_documents(document_id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(tag_id) REFERENCES library_tags(tag_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_document_tags_tag_id_document_id ON library_document_tags(tag_id,document_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS library_pending_restore_tags(document_id TEXT NOT NULL,source_tag_id TEXT NOT NULL,operation_id TEXT NOT NULL,PRIMARY KEY(document_id,source_tag_id),FOREIGN KEY(document_id) REFERENCES library_documents(document_id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(operation_id) REFERENCES library_data_operations(operation_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_library_pending_restore_tags_operation_id ON library_pending_restore_tags(operation_id)")
    }
}
