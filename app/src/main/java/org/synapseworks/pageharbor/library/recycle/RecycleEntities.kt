package org.synapseworks.pageharbor.library.recycle

import androidx.room.*
import org.synapseworks.pageharbor.library.LibraryDocumentEntity

/** Local opaque identity suppression, intentionally retained after content purge. */
@Entity(tableName="library_deleted_identities",primaryKeys=["document_id","source_document_id"],indices=[Index(value=["source_document_id"])])
data class DeletedDocumentIdentity(@ColumnInfo(name="document_id")val documentId:String,@ColumnInfo(name="source_document_id")val sourceDocumentId:String)
@Entity(tableName="library_document_origins",primaryKeys=["document_id","source_document_id"],
    foreignKeys=[ForeignKey(entity=LibraryDocumentEntity::class,parentColumns=["document_id"],childColumns=["document_id"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["source_document_id"])])
data class DocumentArchiveOrigin(@ColumnInfo(name="document_id")val documentId:String,@ColumnInfo(name="source_document_id")val sourceDocumentId:String)

data class RecyclePage(val items:List<LibraryDocumentEntity> = emptyList(),val count:Long=0,val offset:Int=0)
data class RecyclePreview(val count:Long,val watermark:Long)
enum class RecycleFailure { UNAVAILABLE, NOT_FOUND, CHANGED, IN_USE, STORAGE, STATE_UNCERTAIN }
sealed interface RecycleResult {
    data class Done(val cleanupPending:Boolean=false):RecycleResult
    data class Failed(val reason:RecycleFailure):RecycleResult
}
data class EmptyBinResult(val deleted:Int,val failed:Int,val cleanupPending:Int)
