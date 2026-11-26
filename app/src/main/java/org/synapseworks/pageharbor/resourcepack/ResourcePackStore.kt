package org.synapseworks.pageharbor.resourcepack

import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Data only. No code/model loader, network, or document-library ownership. */
class ResourcePackStore(private val root: File) {
    data class Entry(val path: String, val size: Long, val hash: String)
    data class Pack(val id: String, val version: Int, val provenance: String, val license: String, val hardware: String, val manifestHash: String, val entries: List<Entry>)
    companion object {
        private val monitor = Any()
        private val leases = mutableMapOf<String, Int>()
        const val MAX_ARCHIVE = 8L * 1024 * 1024
        const val MAX_EXPANDED = 16L * 1024 * 1024
        const val MAX_STORE = 64L * 1024 * 1024
        private fun reject(): Nothing = throw IllegalArgumentException("Pack declined: invalid, unsupported, conflicting or over limit")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    init { synchronized(monitor) { check(root.mkdirs() || root.isDirectory); root.listFiles()?.filter { it.name.startsWith(".incoming-") }?.forEach { check(it.deleteRecursively()) } } }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream(); val b=ByteArray(8192)
        while(out.size()<limit) { val n=input.read(b,0,minOf(b.size,limit-out.size())); if(n<0)break; out.write(b,0,n) }; return out.toByteArray()
    }
    private fun exact(input: InputStream, size: Int, checkCancelled: () -> Unit = {}): ByteArray {
        val data=ByteArray(size);var used=0
        while(used<size){checkCancelled();val n=input.read(data,used,size-used);if(n<0)reject();used+=n}
        checkCancelled();if(input.read()!=-1)reject();return data
    }
    private fun validateText(data: ByteArray, checkCancelled: () -> Unit) {
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        java.io.InputStreamReader(java.io.ByteArrayInputStream(data),decoder).use { reader->val chars=CharArray(4096);while(true){checkCancelled();if(reader.read(chars)<0)break} }
    }
    private fun key(id: String) = root.canonicalPath + "/" + id
    private fun parse(bytes: ByteArray): Pack {
        if (bytes.size > 8192) reject()
        val fields = mutableMapOf<String, String>(); val entries = mutableListOf<Entry>()
        text(bytes).lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val pair = line.split('=', limit=2); if (pair.size != 2) reject()
            if (pair[0] == "file") {
                val v = pair[1].split('|'); if (v.size != 3 || !v[0].matches(Regex("data/[a-z0-9_-]{1,64}\\.txt"))) reject()
                val size = v[1].toLongOrNull() ?: reject(); if (size !in 0..2L*1024*1024 || !v[2].matches(Regex("[a-f0-9]{64}"))) reject()
                entries.add(Entry(v[0],size,v[2])); if (entries.size > 64) reject()
            } else if (fields.put(pair[0], pair[1]) != null) reject()
        }
        if (fields.keys != setOf("schema","id","version","compatibility","provenance","license","hardware") || fields["schema"] != "1" || fields["compatibility"] != "rme-text-resource-v1") reject()
        val id = fields.getValue("id"); val version = fields.getValue("version").toIntOrNull() ?: reject()
        if (!id.matches(Regex("[a-z][a-z0-9-]{0,47}")) || version !in 1..1000000 || entries.isEmpty() || entries.map { it.path }.distinct().size != entries.size || entries.sumOf { it.size } > MAX_EXPANDED) reject()
        for (name in listOf("provenance","license","hardware")) if (fields.getValue(name).length !in 1..512 || fields.getValue(name).any { it.code !in 32..126 }) reject()
        return Pack(id,version,fields.getValue("provenance"),fields.getValue("license"),fields.getValue("hardware"),hash(bytes),entries)
    }
    fun list(): List<Pack> = synchronized(monitor) { root.listFiles().orEmpty().filter { !it.name.startsWith('.') && it.isDirectory }.map { dir -> val f=File(dir,"pack.properties"); if (f.length()>8192) reject(); parse(f.inputStream().use { bounded(it,8193) }).also { if(it.id != dir.name) reject() } }.sortedBy { it.id } }
    fun import(input: InputStream, checkCancelled: () -> Unit = {}): Pack = synchronized(monitor) {
        val stage=File(root,".incoming-${UUID.randomUUID()}"); check(stage.mkdir())
        try {
            if (list().size >= 64 || root.usableSpace < 56L*1024*1024 || root.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_STORE-MAX_EXPANDED-MAX_ARCHIVE-8192) reject()
            val archive=File(stage,"input.zip"); var copied=0L
            archive.outputStream().use { out -> val buffer=ByteArray(32768); while(true) { checkCancelled(); val n=input.read(buffer); if(n<0)break; copied+=n; if(copied>MAX_ARCHIVE)reject(); out.write(buffer,0,n) } }
            val content=File(stage,"content"); check(content.mkdir())
            val pack=ZipFile(archive).use { zip ->
                val names=mutableSetOf<String>(); val enumeration=zip.entries()
                while(enumeration.hasMoreElements()) { checkCancelled(); val e=enumeration.nextElement(); if(names.size>=65 || !names.add(e.name) || e.isDirectory)reject() }
                val meta=zip.getEntry("pack.properties") ?: reject()
                val bytes=zip.getInputStream(meta).use { bounded(it,8193) }; val p=parse(bytes)
                if(names != p.entries.map { it.path }.toSet()+"pack.properties" || File(root,p.id).exists())reject()
                var expanded=0L
                for(e in p.entries) {
                    checkCancelled(); val target=File(content,e.path); check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val data=zip.getInputStream(zip.getEntry(e.path)).use { exact(it,e.size.toInt(),checkCancelled) }; expanded+=data.size
                    if(data.size.toLong()!=e.size || expanded>MAX_EXPANDED || hash(data)!=e.hash)reject()
                    validateText(data,checkCancelled) // strict UTF-8 validation without a full payload String
                    target.outputStream().use { it.write(data); it.fd.sync() }
                }
                File(content,"pack.properties").outputStream().use { it.write(bytes); it.fd.sync() }; p
            }
            checkCancelled(); if(root.usableSpace<32L*1024*1024)reject()
            check(content.renameTo(File(root,pack.id))) { "Pack publication failed" }; pack
        } finally { check(stage.deleteRecursively()) { "Pack staging cleanup failed" } }
    }
    fun remove(id: String): Boolean = synchronized(monitor) {
        val pack=list().firstOrNull { it.id==id } ?: return@synchronized false
        if((leases[key(pack.id)] ?: 0)>0)return@synchronized false
        val dir=File(root,pack.id); val retired=File(root,".incoming-${UUID.randomUUID()}")
        check(dir.renameTo(retired)); check(retired.deleteRecursively()); true
    }
    fun <T> usePack(id: String, action: (Pack, File) -> T): T {
        val pack=synchronized(monitor) { val p=list().firstOrNull { it.id==id } ?: throw IllegalStateException("Resource unavailable: import a compatible local text pack")
            p.entries.forEach { val f=File(root,"${p.id}/${it.path}"); if(f.length()!=it.size || hash(f.inputStream().use { input -> exact(input,it.size.toInt()) })!=it.hash)reject() }
            leases[key(id)]=(leases[key(id)] ?: 0)+1; p }
        try { return action(pack,File(root,id)) } finally { synchronized(monitor) { val remaining=(leases[key(id)] ?: 1)-1; if(remaining==0)leases.remove(key(id)) else leases[key(id)]=remaining } }
    }
}
