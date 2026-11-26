package org.synapseworks.pageharbor.library.organization

import androidx.room.*
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDataOperationEntity

@Entity(tableName="library_tags",indices=[Index(value=["normalized_name"],unique=true)])
data class LibraryTagEntity(@PrimaryKey @ColumnInfo(name="tag_id")val tagId:String,val name:String,@ColumnInfo(name="normalized_name")val normalizedName:String)
@Entity(tableName="library_document_tags",primaryKeys=["document_id","tag_id"],
    foreignKeys=[ForeignKey(entity=LibraryDocumentEntity::class,parentColumns=["document_id"],childColumns=["document_id"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=LibraryTagEntity::class,parentColumns=["tag_id"],childColumns=["tag_id"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["tag_id","document_id"])])
data class LibraryDocumentTagEntity(@ColumnInfo(name="document_id")val documentId:String,@ColumnInfo(name="tag_id")val tagId:String)
@Entity(tableName="library_pending_restore_tags",primaryKeys=["document_id","source_tag_id"],
    foreignKeys=[ForeignKey(entity=LibraryDocumentEntity::class,parentColumns=["document_id"],childColumns=["document_id"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=LibraryDataOperationEntity::class,parentColumns=["operation_id"],childColumns=["operation_id"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["operation_id"])])
data class LibraryPendingRestoreTagEntity(@ColumnInfo(name="document_id")val documentId:String,@ColumnInfo(name="source_tag_id")val sourceTagId:String,@ColumnInfo(name="operation_id")val operationId:String)

data class DocumentOrganizationRow(@ColumnInfo(name="document_id")val documentId:String,val title:String,@ColumnInfo(name="is_favorite")val favorite:Boolean,@ColumnInfo(name="tag_id")val tagId:String?)
