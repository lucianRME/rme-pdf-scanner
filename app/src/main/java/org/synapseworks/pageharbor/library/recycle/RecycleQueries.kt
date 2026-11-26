package org.synapseworks.pageharbor.library.recycle

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.synapseworks.pageharbor.library.*

/** Inherited by LibraryDao so all state, identity and search changes share its transaction. */
abstract class RecycleQueries {
    @Query("SELECT * FROM library_documents WHERE document_id=:id LIMIT 1")
    abstract suspend fun recycleDocument(id:String):LibraryDocumentEntity?
    @Query("SELECT * FROM library_documents WHERE library_state='TRASHED' ORDER BY trash_revision DESC,document_id LIMIT 30 OFFSET :offset")
    abstract fun observeRecycleRows(offset:Int):Flow<List<LibraryDocumentEntity>>
    @Query("SELECT COUNT(*) FROM library_documents WHERE library_state='TRASHED'")
    abstract fun observeRecycleCount():Flow<Long>
    @Query("SELECT COUNT(*) AS count,COALESCE(MAX(trash_revision),0) AS watermark FROM library_documents WHERE library_state='TRASHED'")
    abstract suspend fun recyclePreview():RecyclePreview
    @Query("SELECT * FROM library_documents WHERE library_state='TRASHED' AND trash_revision<=:watermark AND document_id>:after ORDER BY document_id LIMIT 30")
    abstract suspend fun recyclePurgePage(watermark:Long,after:String):List<LibraryDocumentEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM library_deleted_identities WHERE source_document_id=:sourceId)")
    abstract suspend fun isDeletedArchiveIdentity(sourceId:String):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM library_deleted_identities WHERE document_id=:id)")
    abstract suspend fun isDeletedLocalIdentity(id:String):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM library_documents WHERE library_state='TRASHED' AND content_hash_version=:version AND content_sha256=:sha)")
    abstract suspend fun hasTrashedContent(version:Int,sha:String):Boolean
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    abstract suspend fun rememberArchiveOrigin(origin:DocumentArchiveOrigin)
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDeletedIdentity(row:DeletedDocumentIdentity)
    @Query("INSERT OR IGNORE INTO library_deleted_identities(document_id,source_document_id) SELECT document_id,source_document_id FROM library_document_origins WHERE document_id=:id")
    protected abstract suspend fun markDeletedOrigins(id:String)
    @Query("DELETE FROM library_deleted_identities WHERE document_id=:id")
    protected abstract suspend fun forgetDeletedIdentities(id:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun recycleInsertMetadata(row:LibraryMetadataEntity)
    @Query("SELECT library_revision FROM library_metadata WHERE metadata_id='library'")
    protected abstract suspend fun recycleRevision():Long
    @Query("UPDATE library_metadata SET library_revision=library_revision+1,modified_at=:now WHERE metadata_id='library'")
    protected abstract suspend fun recycleBump(now:Long)
    @Query("UPDATE library_documents SET library_state='TRASHED',trashed_at=:now,trash_revision=:revision WHERE document_id=:id AND library_state='ACTIVE'")
    protected abstract suspend fun moveToTrash(id:String,now:Long,revision:Long):Int
    @Query("UPDATE library_documents SET library_state='ACTIVE',trashed_at=NULL,trash_revision=0 WHERE document_id=:id AND library_state='TRASHED' AND trash_revision=:revision")
    protected abstract suspend fun restoreFromTrash(id:String,revision:Long):Int
    @Query("DELETE FROM library_documents WHERE document_id=:id AND library_state='TRASHED' AND trash_revision=:revision")
    protected abstract suspend fun deleteTrashedRow(id:String,revision:Long):Int
    @Transaction open suspend fun trashSavedDocument(id:String,now:Long):LibraryDocumentEntity? {
        val row=recycleDocument(id)?:return null
        if(row.libraryState=="TRASHED")return row
        if(row.libraryState!="ACTIVE")return null
        recycleInsertMetadata(LibraryMetadataEntity(modifiedAtMillis=now))
        val revision=recycleRevision()+1
        insertDeletedIdentity(DeletedDocumentIdentity(id,id));markDeletedOrigins(id)
        check(moveToTrash(id,now,revision)==1);recycleBump(now)
        return recycleDocument(id)
    }
    @Transaction open suspend fun restoreRecycleRow(id:String,revision:Long,now:Long):LibraryDocumentEntity? {
        val row=recycleDocument(id)?:return null
        if(row.libraryState=="ACTIVE")return row
        if(row.libraryState!="TRASHED"||row.trashRevision!=revision)return null
        check(restoreFromTrash(id,revision)==1);forgetDeletedIdentities(id);recycleBump(now)
        return recycleDocument(id)
    }
    @Transaction open suspend fun deleteRecycleRow(id:String,revision:Long,now:Long):LibraryDocumentEntity? {
        val row=recycleDocument(id)?:return null
        if(row.libraryState!="TRASHED"||row.trashRevision!=revision)return null
        check(deleteTrashedRow(id,revision)==1);recycleBump(now)
        return row
    }
}
