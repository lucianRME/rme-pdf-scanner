package org.synapseworks.pageharbor.resourcepack

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Each model store accepts one pinned ZIP and holds a lease until native work closes. */
class ModelPackStore internal constructor(private val root: File, private val profile: Profile) {
    constructor(root: File) : this(root, legacy)

    data class Entry(val path:String,val size:Long,val hash:String)

    internal data class Profile(
        val id: String,
        val metadata: String,
        val archiveLimit: Long,
        val archiveSha: String,
        val freeBefore: Long,
        val freeAfterStaging: Long,
        val entries: List<Entry>,
    )
    companion object {
        const val ID="opus-en-ro-v1"
        const val ARCHIVE_LIMIT=128L*1024*1024
        const val ARCHIVE_SHA="9afe2f31534370774c0ee487ba617fe81de7a81d8599a93cb9d8ab36477c865f"
        const val STORE_LIMIT=512L*1024*1024
        val entries=listOf(
            Entry("encoder_model_quantized.onnx",50105246L,"843d42689aeba847c71e88863d9d6fbba43cac21d115f375e90afc9922ceadc6"),
            Entry("decoder_model_quantized.onnx",57025774L,"750ae52709f955bc95aec9cf7a4ea3d9e5525fc164e58b36a3a6424bfafd921f"),
            Entry("tokenizer-source-adapter.json",1371099L,"49d4837691db05f6b446acd5b73c209b99642179c64253c6aae6df8be0ccbcf2"),
            Entry("source-to-model-ids.json",217071L,"48805c8c47e6e78920f3f396ac5ced55121a53497d65c8ae329c34f061dc352c"),
            Entry("target-decoder-data.json",451257L,"7c176940fd3cf91c05c0d21a98598d72b81ad3cd7c782bad2421600e093385b8"),
            Entry("vocab.json",1511210L,"f24171dbbd2c2564271dbe811971ee3482bc6431a74817811dbb4783bb67e331"),
            Entry("expected-tokenizer-cases.json",4334L,"45d220c2856e74d50f52f4c150480dfad22c730d314d4485387a2249e9900189"),
            Entry("expected-target-decode.json",1170L,"8648aa8c49e5c4304a3eae44cf0178ac8f6d758e81a8478fb4970681a49a50b1")
        )
        private val monitor=Any()
        private val leases=mutableMapOf<String,Int>()
        const val METADATA="schema=1\nid=opus-en-ro-v1\ncompatibility=rme-en-ro-check-v1\nversion=1\n"
        private val legacy = Profile(
            ID, METADATA, ARCHIVE_LIMIT, ARCHIVE_SHA,
            freeBefore = 768L * 1024 * 1024,
            freeAfterStaging = STORE_LIMIT,
            entries = entries,
        )

        fun sha(file: File): String = sha(file) {}

        private fun sha(file: File, cancel: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    cancel()
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private val key get()=root.canonicalPath
    init{synchronized(monitor){check(root.mkdirs()||root.isDirectory);root.listFiles()?.filter{it.name.startsWith(".incoming-")}?.forEach{check(it.deleteRecursively())}}}
    fun installed():Boolean=synchronized(monitor){File(root,profile.id).isDirectory}
    fun import(input:InputStream,cancel:()->Unit={})=synchronized(monitor){
        check(!installed());require(root.listFiles().orEmpty().isEmpty());check(root.usableSpace>=profile.freeBefore)
        val stage=File(root,".incoming-${UUID.randomUUID()}");check(stage.mkdir())
        try{
            val zipFile=File(stage,"pack.zip");var count=0L
            zipFile.outputStream().use{out->val b=ByteArray(32768);while(true){cancel();val n=input.read(b);if(n<0)break;count+=n;require(count<=profile.archiveLimit);out.write(b,0,n)}}
            require(sha(zipFile,cancel)==profile.archiveSha) // Refuse unknown ZIP structure before parser allocation.
            val content=File(stage,"content");check(content.mkdir())
            ZipFile(zipFile).use{zip->val names=mutableSetOf<String>();val all=zip.entries();while(all.hasMoreElements()){cancel();val e=all.nextElement();require(!e.isDirectory&&names.size<profile.entries.size+1&&names.add(e.name))};require(names==profile.entries.map{it.path}.toSet()+"model.properties")
                val meta=zip.getInputStream(zip.getEntry("model.properties")).use{stream->val out=java.io.ByteArrayOutputStream();repeat(profile.metadata.toByteArray().size+1){val c=stream.read();if(c>=0)out.write(c)};out.toByteArray()};require(meta.contentEquals(profile.metadata.toByteArray()))
                for(e in profile.entries){cancel();val f=File(content,e.path);var n=0L
                    zip.getInputStream(zip.getEntry(e.path)).use{inputFile->f.outputStream().use{out->val b=ByteArray(32768);while(true){cancel();val k=inputFile.read(b);if(k<0)break;n+=k;require(n<=e.size);out.write(b,0,k)};out.fd.sync()}}
                    require(n==e.size&&sha(f,cancel)==e.hash)
                }
            }
            cancel();check(root.usableSpace>=profile.freeAfterStaging);check(content.renameTo(File(root,profile.id)))
        }finally{check(stage.deleteRecursively())}
    }
    fun remove():Boolean=synchronized(monitor){if((leases[key]?:0)>0)return@synchronized false;if(!installed())return@synchronized false;val retired=File(root,".incoming-${UUID.randomUUID()}");check(File(root,profile.id).renameTo(retired));check(retired.deleteRecursively());true}
    fun <T> usePack(cancel:()->Unit={},action:(File)->T):T {
        val dir=synchronized(monitor){check(installed());val dir=File(root,profile.id);require(dir.listFiles().orEmpty().map{it.name}.toSet()==profile.entries.map{it.path}.toSet());profile.entries.forEach{cancel();val f=File(dir,it.path);require(f.isFile&&f.length()==it.size&&sha(f)==it.hash)};leases[key]=(leases[key]?:0)+1;dir}
        try{return action(dir)}finally{synchronized(monitor){val n=checkNotNull(leases[key])-1;if(n==0)leases.remove(key)else leases[key]=n}}
    }
}
