package org.synapseworks.pageharbor.document.signature

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val MAX_SIGNATURE_TEMPLATES = 32
const val MAX_SIGNATURE_ARCHIVE = 4 * 1024 * 1024
class SignatureTemplate(val id: String, val name: String, val ink: HandwrittenSignature) {
    init {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        require(name.length in 1..80 && name.isNotBlank() && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() || Character.isSurrogate(it) })
    }
    override fun toString() = "SignatureTemplate"
}
/** Bounded private library codec. Document and automatic backups never enumerate this library. */
object SignatureTemplateArchive {
    fun encode(templates: List<SignatureTemplate>): ByteArray {
        require(templates.size <= MAX_SIGNATURE_TEMPLATES && templates.map { it.id }.distinct().size == templates.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x524d4553); out.writeInt(1); out.writeInt(templates.size)
            templates.forEach { t -> out.writeUTF(t.id); out.writeUTF(t.name); val ink = t.ink.encoding.toByteArray(Charsets.US_ASCII); out.writeInt(ink.size); out.write(ink) }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_SIGNATURE_ARCHIVE) }
    }
    fun decode(bytes: ByteArray): List<SignatureTemplate> {
        require(bytes.size <= MAX_SIGNATURE_ARCHIVE)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x524d4553 && input.readInt() == 1)
                val count = input.readInt(); require(count in 0..MAX_SIGNATURE_TEMPLATES)
                val templates = List(count) {
                    val id = input.readUTF(); val name = input.readUTF(); val size = input.readInt()
                    require(size in 1..MAX_SIGNATURE_ENCODING && size <= input.available())
                    val ink = ByteArray(size); input.readFully(ink)
                    SignatureTemplate(id,name,HandwrittenSignature.decode(String(ink,Charsets.US_ASCII)))
                }
                require(input.read() == -1 && templates.map { it.id }.distinct().size == count)
                return Collections.unmodifiableList(templates)
            }
        } catch (_: Exception) { throw IOException("Signature library is invalid") }
    }
}
/** One atomically replaced file below noBackupFilesDir. Sharing instances is required. */
class SignatureTemplateStore private constructor(directoryProvider: () -> File) {
    constructor(directory: File): this({ directory })
    private constructor(context: android.content.Context): this({ File(context.noBackupFilesDir,"signatures") })
    // Resolve/create the Android no-backup directory only when an IO operation first needs it.
    private val directory: File by lazy(directoryProvider)
    private val mutex = Mutex()
    private val file get() = File(directory,"templates.rmesignatures")
    private suspend fun read(): List<SignatureTemplate> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() <= MAX_SIGNATURE_ARCHIVE)
        val bytes=file.inputStream().use { input ->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){coroutineContext.ensureActive();val count=input.read(buffer);if(count<0)break;require(output.size()+count<=MAX_SIGNATURE_ARCHIVE);output.write(buffer,0,count)}
            output.toByteArray()
        }
        return SignatureTemplateArchive.decode(bytes)
    }
    suspend fun list(): List<SignatureTemplate> = withContext(Dispatchers.IO) { mutex.withLock { File(directory,"pending").delete(); read() } }
    private suspend fun update(change: (List<SignatureTemplate>) -> List<SignatureTemplate>): List<SignatureTemplate> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = change(read()); val bytes = SignatureTemplateArchive.encode(next)
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory,"pending")
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                coroutineContext.ensureActive()
                Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                // Rename is the commit point. Cancellation after it cannot roll back an accepted explicit Save.
                Collections.unmodifiableList(next.toList())
            } finally { temporary.delete() }
        }
    }
    suspend fun save(name: String, ink: HandwrittenSignature, id: String? = null): List<SignatureTemplate> = update { old ->
        val entry = SignatureTemplate(id ?: UUID.randomUUID().toString(),name,ink)
        if (id == null) { if(old.any{it.name==name&&it.ink==ink})old else old + entry } else { require(old.any { it.id == id }); old.map { if (it.id == id) entry else it } }
    }
    suspend fun delete(id: String): List<SignatureTemplate> = update { it.filterNot { t -> t.id == id } }
    companion object {
        @Volatile private var instance: SignatureTemplateStore? = null
        fun get(context: android.content.Context): SignatureTemplateStore = instance ?: synchronized(this) {
            instance ?: SignatureTemplateStore(context.applicationContext).also { instance = it }
        }
    }
}
