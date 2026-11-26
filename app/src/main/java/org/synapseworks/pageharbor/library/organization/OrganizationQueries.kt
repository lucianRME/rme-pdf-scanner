package org.synapseworks.pageharbor.library.organization

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import org.synapseworks.pageharbor.library.*

/** Narrow metadata queries inherited by LibraryDao so restore activation shares its transaction. */
abstract class OrganizationQueries : org.synapseworks.pageharbor.library.recycle.RecycleQueries() {
    @Query("SELECT * FROM library_tags ORDER BY normalized_name,tag_id LIMIT 257")
    abstract fun observeTags():Flow<List<LibraryTagEntity>>
    @Query("SELECT * FROM library_tags ORDER BY tag_id LIMIT 257")
    abstract suspend fun organizationTags():List<LibraryTagEntity>
    @Query("SELECT * FROM library_tags WHERE tag_id=:id")
    abstract suspend fun organizationTag(id:String):LibraryTagEntity?
    @Query("SELECT * FROM library_tags WHERE normalized_name=:key")
    protected abstract suspend fun tagWithName(key:String):LibraryTagEntity?
    @Query("SELECT COUNT(*) FROM library_tags")
    protected abstract suspend fun tagCount():Int
    @Insert protected abstract suspend fun insertTag(tag:LibraryTagEntity)
    @Update protected abstract suspend fun updateTag(tag:LibraryTagEntity):Int
    @Query("DELETE FROM library_tags WHERE tag_id=:id")
    protected abstract suspend fun deleteTagRow(id:String):Int
    @Query("SELECT document_id FROM library_documents WHERE document_id=:id AND library_state='ACTIVE'")
    protected abstract suspend fun organizationActiveDocument(id:String):String?
    @Query("SELECT tag_id FROM library_document_tags WHERE document_id=:id ORDER BY tag_id LIMIT 33")
    abstract suspend fun documentTagIds(id:String):List<String>
    /** At most256 document rows /8192 links from the snapshot's existing keyset batch. */
    @Query("SELECT a.* FROM library_document_tags a JOIN library_documents d ON d.document_id=a.document_id WHERE d.library_state='ACTIVE' AND d.row_id>:afterRowId AND d.row_id<=:throughRowId ORDER BY d.row_id,a.tag_id LIMIT 8193")
    abstract suspend fun backupDocumentTagsPage(afterRowId:Long,throughRowId:Long):List<LibraryDocumentTagEntity>
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAssignment(row:LibraryDocumentTagEntity):Long
    @Query("DELETE FROM library_document_tags WHERE document_id=:documentId AND tag_id=:tagId")
    protected abstract suspend fun deleteAssignment(documentId:String,tagId:String):Int
    @Query("UPDATE library_documents SET is_favorite=:value WHERE document_id=:id AND library_state='ACTIVE'")
    protected abstract suspend fun updateFavorite(id:String,value:Boolean):Int
    @Query("SELECT d.document_id,d.title,d.is_favorite,a.tag_id FROM library_documents d LEFT JOIN library_document_tags a ON a.document_id=d.document_id WHERE d.document_id=:id AND d.library_state='ACTIVE' ORDER BY a.tag_id LIMIT 33")
    abstract fun observeDocumentOrganization(id:String):Flow<List<DocumentOrganizationRow>>
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    protected abstract suspend fun organizationInsertMetadata(row:LibraryMetadataEntity):Long
    @Query("UPDATE library_metadata SET library_revision=library_revision+1,modified_at=:now WHERE metadata_id='library'")
    protected abstract suspend fun organizationIncrementRevision(now:Long):Int
    private suspend fun changed(now:Long){organizationInsertMetadata(LibraryMetadataEntity(modifiedAtMillis=now));check(organizationIncrementRevision(now)==1)}
    private suspend fun requireDocument(id:String){if(organizationActiveDocument(id)==null)throw OrganizationConstraint(OrganizationFailure.NOT_FOUND)}
    private suspend fun assign(documentId:String,tagId:String,value:Boolean){
        requireDocument(documentId);if(organizationTag(tagId)==null)throw OrganizationConstraint(OrganizationFailure.NOT_FOUND)
        if(value){val ids=documentTagIds(documentId);if(tagId !in ids&&ids.size>=LibraryTagRules.MAX_DOCUMENT_TAGS)throw OrganizationConstraint(OrganizationFailure.ASSIGNMENT_LIMIT);insertAssignment(LibraryDocumentTagEntity(documentId,tagId))}
        else deleteAssignment(documentId,tagId)
    }
    @Transaction open suspend fun createOrganizationTag(name:LibraryTagName,documentId:String?,now:Long):String {
        if(tagWithName(name.key)!=null)throw OrganizationConstraint(OrganizationFailure.NAME_EXISTS)
        if(tagCount()>=LibraryTagRules.MAX_TAGS)throw OrganizationConstraint(OrganizationFailure.TAG_LIMIT)
        if(documentId!=null)requireDocument(documentId)
        val id=UUID.randomUUID().toString();insertTag(LibraryTagEntity(id,name.display,name.key))
        if(documentId!=null)assign(documentId,id,true)
        changed(now);return id
    }
    @Transaction open suspend fun renameOrganizationTag(id:String,name:LibraryTagName,now:Long){
        val tag=organizationTag(id)?:throw OrganizationConstraint(OrganizationFailure.NOT_FOUND)
        val collision=tagWithName(name.key);if(collision!=null&&collision.tagId!=id)throw OrganizationConstraint(OrganizationFailure.NAME_EXISTS)
        check(updateTag(tag.copy(name=name.display,normalizedName=name.key))==1);changed(now)
    }
    @Transaction open suspend fun deleteOrganizationTag(id:String,now:Long){
        if(deleteTagRow(id)!=1)throw OrganizationConstraint(OrganizationFailure.NOT_FOUND);changed(now)
    }
    @Transaction open suspend fun setOrganizationAssignment(documentId:String,tagId:String,value:Boolean,now:Long){assign(documentId,tagId,value);changed(now)}
    @Transaction open suspend fun setOrganizationFavorite(documentId:String,value:Boolean,now:Long){
        if(updateFavorite(documentId,value)!=1)throw OrganizationConstraint(OrganizationFailure.NOT_FOUND);changed(now)
    }
    @Insert protected abstract suspend fun insertPendingTagRows(rows:List<LibraryPendingRestoreTagEntity>)
    @Query("SELECT * FROM library_pending_restore_tags WHERE operation_id=:operationId AND document_id>:afterDocumentId ORDER BY document_id,source_tag_id LIMIT :limit")
    abstract suspend fun pendingTagRows(operationId:String,afterDocumentId:String,limit:Int):List<LibraryPendingRestoreTagEntity>
    @Query("DELETE FROM library_pending_restore_tags WHERE operation_id=:operationId")
    protected abstract suspend fun deletePendingTagRows(operationId:String)
    @Transaction open suspend fun stageOrganizationAssignments(operationId:String,documentId:String,ids:List<String>){
        require(ids.size<=LibraryTagRules.MAX_DOCUMENT_TAGS&&ids.distinct().size==ids.size)
        if(ids.isNotEmpty())insertPendingTagRows(ids.map{LibraryPendingRestoreTagEntity(documentId,it,operationId)})
    }
    /** Called inside the single restore activation transaction, after pending documents turn active. */
    open suspend fun activateOrganization(operationId:String,definitions:List<LibraryTagEntity>){
        require(definitions.size<=LibraryTagRules.MAX_TAGS)
        val mapping=mutableMapOf<String,String>()
        for(definition in definitions){
            val name=requireNotNull(LibraryTagRules.name(definition.name));require(name.display==definition.name&&name.key==definition.normalizedName)
            val existing=tagWithName(name.key)
            val id=if(existing!=null)existing.tagId else {
                if(tagCount()>=LibraryTagRules.MAX_TAGS)throw OrganizationConstraint(OrganizationFailure.TAG_LIMIT)
                UUID.randomUUID().toString().also{insertTag(LibraryTagEntity(it,name.display,name.key))}
            }
            check(mapping.put(definition.tagId,id)==null)
        }
        // Bounded keyset batches; never materialize the whole assignment relation.
        var after=""
        while(true){
            val rows=pendingTagRows(operationId,after,LibraryTagRules.MAX_DOCUMENT_TAGS*30)
            if(rows.isEmpty())break
            // Do not advance past a partially returned document; fetch its complete <=32 links separately.
            val last=rows.last().documentId
            val complete=if(rows.size==LibraryTagRules.MAX_DOCUMENT_TAGS*30)rows.filter{it.documentId!=last}else rows
            if(complete.isEmpty())error("Invalid pending assignments")
            for(row in complete){val id=requireNotNull(mapping[row.sourceTagId]);requireDocument(row.documentId);insertAssignment(LibraryDocumentTagEntity(row.documentId,id))}
            after=complete.last().documentId
        }
        deletePendingTagRows(operationId)
    }
    @RawQuery(observedEntities=[LibraryDocumentEntity::class,LibraryTagEntity::class,LibraryDocumentTagEntity::class,LibraryFolderEntity::class,LibraryPageEntity::class,LibraryDocumentSearchContentEntity::class,LibraryPageSearchContentEntity::class])
    abstract fun observeOrganizationPage(query:SupportSQLiteQuery):Flow<List<OrganizationBrowseRow>>
}
