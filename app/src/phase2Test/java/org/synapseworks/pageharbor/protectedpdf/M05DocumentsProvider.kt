package org.synapseworks.pageharbor.protectedpdf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Synthetic destination only; this source set and root never enter production. */
class M05DocumentsProvider : DocumentsProvider() {
    private val root: File get() = File(requireNotNull(context).cacheDir, "m05-system-destination").apply { mkdirs() }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        return MatrixCursor(columns).apply { addRow(columns.map {
            when (it) {
                Root.COLUMN_ROOT_ID -> "m05"
                Root.COLUMN_DOCUMENT_ID -> "root"
                Root.COLUMN_TITLE -> "RME test destination"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY
                Root.COLUMN_MIME_TYPES -> "application/pdf"
                else -> null
            }
        }.toTypedArray()) }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        documents(projection, listOf(documentId))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "root")
        return documents(projection, root.listFiles().orEmpty().map { it.name })
    }
    private fun documents(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        return MatrixCursor(columns).apply { ids.forEach { id ->
            val file = if (id == "root") root else resolve(id)
            addRow(columns.map { when (it) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> if (id == "root") "RME test destination" else "Synthetic encrypted PDF"
                Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "application/pdf"
                Document.COLUMN_FLAGS -> if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                Document.COLUMN_SIZE -> if (id == "root") null else file.length()
                else -> null
            } }.toTypedArray())
        } }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root" && mimeType == "application/pdf")
        return UUID.randomUUID().toString().also { resolve(it).createNewFile() }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (denyWrites && mode != "r") throw FileNotFoundException("Synthetic destination unavailable")
        return ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode))
    }
    override fun deleteDocument(documentId: String) { check(resolve(documentId).delete()) }
    private fun resolve(id: String): File {
        require(id != "root" && id.none { it == '/' || it.code == 92 } && id != "." && id != "..")
        return File(root, id)
    }
    companion object { @Volatile var denyWrites = false }
}
