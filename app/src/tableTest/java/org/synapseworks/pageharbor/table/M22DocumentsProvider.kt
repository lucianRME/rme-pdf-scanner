package org.synapseworks.pageharbor.table

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
class M22DocumentsProvider : DocumentsProvider() {
    private val root: File get() = File(requireNotNull(context).cacheDir, "m22-system-destination").apply { mkdirs() }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        return MatrixCursor(columns).apply { addRow(columns.map {
            when (it) {
                Root.COLUMN_ROOT_ID -> "m22"
                Root.COLUMN_DOCUMENT_ID -> "root"
                Root.COLUMN_TITLE -> "RME CSV test destination"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY
                Root.COLUMN_MIME_TYPES -> "text/csv"
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
                Document.COLUMN_DISPLAY_NAME -> if (id == "root") "RME CSV test destination" else "Synthetic reviewed CSV"
                Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "text/csv"
                Document.COLUMN_FLAGS -> if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                Document.COLUMN_SIZE -> if (id == "root") null else file.length()
                else -> null
            } }.toTypedArray())
        } }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root" && mimeType == "text/csv")
        return UUID.randomUUID().toString().also { resolve(it).createNewFile() }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val ownerGeneration=generation.get()
        if(mode!="r") {
            opens.incrementAndGet();entered?.countDown()
            release?.let { check(it.await(30,java.util.concurrent.TimeUnit.SECONDS)) }
        }
        if (denyWrites && mode != "r") throw FileNotFoundException("Synthetic destination unavailable")
        return ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode),android.os.Handler(android.os.Looper.getMainLooper())) { if(mode!="r"&&ownerGeneration==generation.get())closes.incrementAndGet() }
    }
    override fun deleteDocument(documentId: String) { check(resolve(documentId).delete()) }
    private fun resolve(id: String): File {
        require(id != "root" && id.none { it == '/' || it.code == 92 } && id != "." && id != "..")
        return File(root, id)
    }
    companion object {
        @Volatile var denyWrites=false
        private val generation=java.util.concurrent.atomic.AtomicInteger()
        val opens=java.util.concurrent.atomic.AtomicInteger();val closes=java.util.concurrent.atomic.AtomicInteger()
        @Volatile var entered:java.util.concurrent.CountDownLatch?=null
        @Volatile var release:java.util.concurrent.CountDownLatch?=null
        fun root(context:android.content.Context)=File(context.cacheDir,"m22-system-destination").apply{mkdirs()}
        fun reset(context:android.content.Context){generation.incrementAndGet();release?.countDown();entered=null;release=null;denyWrites=false;opens.set(0);closes.set(0);root(context).listFiles().orEmpty().forEach{it.delete()}}
        fun create(context:android.content.Context):android.net.Uri=checkNotNull(android.provider.DocumentsContract.createDocument(context.contentResolver,android.provider.DocumentsContract.buildDocumentUri(context.packageName+".m22documents","root"),"text/csv","table.csv"))
    }
}
