package org.synapseworks.pageharbor.workflow

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract as DC
import java.io.InputStream
import java.security.MessageDigest

internal class WorkflowDestination(private val context: Context) {
    private val resolver get() = context.contentResolver
    @Volatile var lastCheck="NOT_CHECKED"; private set
    fun localTree(tree: String, expectedIdentity: String? = null): Boolean = runCatching {
        lastCheck="URI"
        val uri = Uri.parse(tree)
        require(uri.scheme == "content" && uri.authority == AUTHORITY && DC.isTreeUri(uri))
        lastCheck="PRIMARY_SUBFOLDER"
        val id = DC.getTreeDocumentId(uri)
        require(id.startsWith("primary:") && id.length <= 512)
        val parts = id.removePrefix("primary:").split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotBlank() && it !in setOf(".","..") && !it.equals("Android",true) && it.none(Char::isISOControl) })
        lastCheck="PERSISTED_GRANT"
        require(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        lastCheck="SYSTEM_PROVIDER"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(AUTHORITY, 0))
        require(provider.packageName == "com.android.externalstorage" && provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        lastCheck="PLATFORM_SIGNATURE"
        require(context.packageManager.checkSignatures("android", provider.packageName) == PackageManager.SIGNATURE_MATCH)
        // Root inventory queries require MANAGE_DOCUMENTS on the tested platform. Instead
        // verify the actual granted directory descriptor against AOSP's primary-volume mapping.
        lastCheck="LOCAL_DESCRIPTOR"
        val identity=directoryIdentity(uri)
        require(expectedIdentity==null || identity==expectedIdentity)
        lastCheck="DIRECTORY_IDENTITY"
        val doc = document(uri)
        resolver.query(doc,arrayOf(DC.Document.COLUMN_DOCUMENT_ID,DC.Document.COLUMN_MIME_TYPE,DC.Document.COLUMN_FLAGS),null,null,null)?.use { c ->
            require(c.moveToFirst() && c.getString(0) == id && c.getString(1) == DC.Document.MIME_TYPE_DIR && c.getInt(2) and DC.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
        } ?: error("Unavailable")
        lastCheck="VERIFIED"
        true
    }.getOrDefault(false)
    fun identity(tree:String):String? = runCatching { val uri=Uri.parse(tree);require(localTree(tree));directoryIdentity(uri) }.getOrNull()
    private fun localPath(documentId:String):String {
        require(documentId.startsWith("primary:"))
        val root=android.os.Environment.getExternalStorageDirectory().canonicalFile
        val path=java.io.File(root,documentId.removePrefix("primary:")).canonicalFile
        require(path.toPath().startsWith(root.toPath()) && path!=root)
        return path.path
    }
    private fun directoryIdentity(tree:Uri):String {
        val id=DC.getTreeDocumentId(tree)
        return requireNotNull(resolver.openFileDescriptor(document(tree),"r")).use { fd ->
            val stat=android.system.Os.fstat(fd.fileDescriptor)
            require(android.system.OsConstants.S_ISDIR(stat.st_mode))
            require(java.io.File(android.system.Os.readlink("/proc/self/fd/${fd.fd}")).canonicalPath==localPath(id))
            "${stat.st_dev}:${stat.st_ino}"
        }
    }
    fun descriptorLocal(fd:android.os.ParcelFileDescriptor,target:String):Boolean = runCatching {
        val stat=android.system.Os.fstat(fd.fileDescriptor)
        require(android.system.OsConstants.S_ISREG(stat.st_mode))
        require(java.io.File(android.system.Os.readlink("/proc/self/fd/${fd.fd}")).canonicalPath==localPath(DC.getDocumentId(Uri.parse(target))))
        true
    }.getOrDefault(false)
    fun localSource(uri:Uri,fd:android.os.ParcelFileDescriptor):Boolean = runCatching {
        require(uri.scheme=="content" && uri.authority==AUTHORITY && !DC.isTreeUri(uri))
        val provider=requireNotNull(context.packageManager.resolveContentProvider(AUTHORITY,0))
        require(provider.packageName=="com.android.externalstorage" && provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0)
        require(context.packageManager.checkSignatures("android",provider.packageName)==PackageManager.SIGNATURE_MATCH)
        require(descriptorLocal(fd,uri.toString()));true
    }.getOrDefault(false)
    fun sourceGranted(guard:WorkflowSourceGuard):Boolean = runCatching {
        val uri=Uri.parse(guard.uri)
        require(resolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission })
        requireNotNull(resolver.openFileDescriptor(uri,"r")).use { require(localSource(uri,it)) }
        true
    }.getOrDefault(false)
    fun take(tree: Uri): Boolean {
        val previous=resolver.persistedUriPermissions.any { it.uri==tree }
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return localTree(tree.toString()).also { supported -> if(!supported && !previous) release(tree.toString()) }
    }
    fun release(tree: String) { runCatching { resolver.releasePersistableUriPermission(Uri.parse(tree), if(DC.isTreeUri(Uri.parse(tree)))Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    fun collision(tree: String, name: String): Boolean {
        val uri = Uri.parse(tree)
        val children = DC.buildChildDocumentsUriUsingTree(uri,DC.getTreeDocumentId(uri))
        resolver.query(children,arrayOf(DC.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { c ->
            var count = 0
            while(c.moveToNext()) { require(++count <= 512); if(java.text.Normalizer.normalize(c.getString(0),java.text.Normalizer.Form.NFC).equals(name,true)) return true }
        } ?: error("Unavailable")
        return false
    }
    fun create(tree: String, name: String,mime:String="application/pdf"): String = requireNotNull(DC.createDocument(resolver,document(Uri.parse(tree)),mime,name)).toString()
    fun validateTarget(target: String, name: String, tree: String?, requireEmpty: Boolean,mime:String="application/pdf"): Boolean = runCatching {
        val uri=Uri.parse(target);require(uri.scheme=="content")
        if(tree!=null) {
            require(localTree(tree) && uri.authority == AUTHORITY && DC.getTreeDocumentId(uri)==DC.getTreeDocumentId(Uri.parse(tree)))
            val parent=DC.getTreeDocumentId(Uri.parse(tree));val child=DC.getDocumentId(uri)
            require(child.startsWith("$parent/") && !child.removePrefix("$parent/").contains('/'))
        }
        resolver.query(uri,arrayOf(DC.Document.COLUMN_DISPLAY_NAME,DC.Document.COLUMN_SIZE,DC.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
            require(c.moveToFirst() && c.getString(0)==name && c.getString(2)==mime)
            if(requireEmpty) require(!c.isNull(1) && c.getLong(1)==0L)
        } ?: error("Unavailable")
        true
    }.getOrDefault(false)
    private fun document(tree: Uri) = DC.buildDocumentUriUsingTree(tree,DC.getTreeDocumentId(tree))
    companion object { const val AUTHORITY="com.android.externalstorage.documents" }
}
internal fun workflowDigest(input: InputStream, max: Long = WORKFLOW_MAX_PDF, check: () -> Unit = {}): Pair<String,Long> {
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var size=0L
    input.use { while(true) { check();val n=it.read(buffer);if(n<0)break;size+=n;require(size<=max);digest.update(buffer,0,n) } }
    require(size>0)
    return digest.digest().joinToString("") { "%02x".format(it) } to size
}
