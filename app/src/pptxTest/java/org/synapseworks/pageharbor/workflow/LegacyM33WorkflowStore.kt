package org.synapseworks.pageharbor.workflow

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Separate no-backup ownership. Room/library/portable backups never reference these files. */
internal class LegacyM33WorkflowStore(val root: File) {
    private var formatVersion=2
    private val metadata = AtomicFile(File(root, "queue.json"))
    var data = WorkflowData(); private set
    @Volatile var healthy = true; private set
    @Volatile var closed=false; private set
    var busyOwner=false; private set
    private var ownerChannel:java.nio.channels.FileChannel?=null
    private var ownerLock:java.nio.channels.FileLock?=null
    private var reservedRoot:String?=null
    init {
        try {
            check(root.mkdirs() || root.isDirectory)
            val key=root.canonicalPath
            if(!ownedRoots.add(key)){busyOwner=true;error("Queue already owned")}
            reservedRoot=key
            ownerChannel=java.io.RandomAccessFile(File(root,"owner.lock"),"rw").channel
            ownerLock=try { ownerChannel!!.tryLock() } catch(_:java.nio.channels.OverlappingFileLockException) { null }
            if(ownerLock==null) { busyOwner=true;error("Queue already owned") }
            if (metadata.baseFile.exists() || File(root, "queue.json.bak").exists()) {
                val bytes = readMetadata()
                require(bytes.size <= 1024 * 1024)
                val j = JSONObject(String(bytes, Charsets.UTF_8)); require(j.getInt("version") in 1..4);formatVersion=maxOf(2,j.getInt("version"))
                val recipes = j.getJSONArray("recipes").objects().map { x -> WorkflowRecipe(x.getString("id"), x.getInt("version"), x.getString("label"), WorkflowPreset.valueOf(x.getString("preset")), x.getString("template"), x.getString("custom"), x.optional("tree"), false, x.getInt("nextCounter"),x.optional("treeIdentity")) }
                val jobs = j.getJSONArray("jobs").objects().map { x -> WorkflowJob(x.getString("id"),x.getString("recipeId"),x.getInt("recipeVersion"),x.getString("name"),WorkflowPreset.valueOf(x.getString("preset")),x.optional("tree"),x.getLong("revision"),x.getString("owner"),x.getString("duplicateKey"),x.getString("capturedAt"),x.getString("zone"),x.getInt("counter"),x.getString("digest"),x.getLong("bytes"),WorkflowStatus.valueOf(x.getString("status")),x.optional("destination"),x.optional("treeIdentity"),x.optJSONArray("sourceGuards")?.objects()?.map { g -> WorkflowSourceGuard(g.getString("uri"),g.getLong("device"),g.getLong("inode")) }.orEmpty()) }
                require(recipes.size <= 16 && jobs.size <= 16 && recipes.map { it.id }.distinct().size == recipes.size && jobs.map { it.id }.distinct().size == jobs.size)
                require((recipes.map { it.id } + jobs.map { it.id }).all { it.matches(Regex("[a-f0-9-]{36}")) })
                require(jobs.all { it.bytes in 1..WORKFLOW_MAX_PDF && it.digest.matches(Regex("[a-f0-9]{64}")) })
                require(jobs.count { it.status.pending() }<=8)
                require(jobs.all { it.sourceGuards.size<=40 && (!it.preset.excludesSources || j.getInt("version")>=2 && it.sourceGuards.isNotEmpty()) })
                require((recipes.none{it.preset==WorkflowPreset.DOCX}&&jobs.none{it.preset==WorkflowPreset.DOCX})||j.getInt("version")>=3)
                require((recipes.none{it.preset==WorkflowPreset.XLSX}&&jobs.none{it.preset==WorkflowPreset.XLSX})||j.getInt("version")==4)
                require(jobs.filter{it.preset==WorkflowPreset.XLSX}.all{it.bytes<=org.synapseworks.pageharbor.xlsx.XLSX_MAX})
                jobs.forEach { WorkflowNaming.verifyExported(it.name,it.preset.extension) }
                recipes.forEach { require(it.label.length in 1..60 && it.preset.available && it.nextCounter in 1..1000000);WorkflowNaming.name(it.template,it.custom,java.time.Instant.EPOCH,java.time.ZoneId.of("UTC"),it.nextCounter.coerceAtMost(999999),it.preset.extension) }
                val recovered = jobs.map { it.copy(status = when(it.status) { WorkflowStatus.WRITING, WorkflowStatus.PICKER -> WorkflowStatus.UNCERTAIN; WorkflowStatus.QUEUED -> WorkflowStatus.PAUSED; else -> it.status }) }
                val grants=j.optJSONArray("grants")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty();require(grants.size<=32)
                commit(WorkflowData(recipes, recovered, grants))
            }
            val retained = data.jobs.filter { it.status.pending() }.map { "${it.id}.pdf" }.toSet()
            root.listFiles()?.filter { it.name.endsWith(".pdf") && it.name !in retained }?.forEach { it.delete() }
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("capture-") }?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { healthy = false;if(ownerLock==null){runCatching{ownerChannel?.close()};reservedRoot?.let(ownedRoots::remove);reservedRoot=null} }
    }
    fun disable() { healthy=false }
    @Synchronized fun close() {
        if(closed)return
        healthy=false
        runCatching{ownerLock?.release()};runCatching{ownerChannel?.close()};reservedRoot?.let(ownedRoots::remove);reservedRoot=null;closed=true
    }
    fun pdf(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "$id.pdf") }
    fun commit(next: WorkflowData) {
        check(healthy && !closed)
        val recipes = next.recipes.map { r -> JSONObject().put("id",r.id).put("version",r.version).put("label",r.label).put("preset",r.preset.name).put("template",r.template).put("custom",r.custom).put("tree",r.tree).put("enabled",r.enabled).put("nextCounter",r.nextCounter).put("treeIdentity",r.treeIdentity) }
        val jobs = next.jobs.map { j -> JSONObject().put("id",j.id).put("recipeId",j.recipeId).put("recipeVersion",j.recipeVersion).put("name",j.name).put("preset",j.preset.name).put("tree",j.tree).put("revision",j.revision).put("owner",j.owner).put("duplicateKey",j.duplicateKey).put("capturedAt",j.capturedAt).put("zone",j.zone).put("counter",j.counter).put("digest",j.digest).put("bytes",j.bytes).put("status",j.status.name).put("destination",j.destination).put("treeIdentity",j.treeIdentity).put("sourceGuards",JSONArray(j.sourceGuards.map { g -> JSONObject().put("uri",g.uri).put("device",g.device).put("inode",g.inode) })) }
        if(next.recipes.any{it.preset==WorkflowPreset.DOCX}||next.jobs.any{it.preset==WorkflowPreset.DOCX})formatVersion=maxOf(formatVersion,3)
        if(next.recipes.any{it.preset==WorkflowPreset.XLSX}||next.jobs.any{it.preset==WorkflowPreset.XLSX})formatVersion=4
        val bytes = JSONObject().put("version",formatVersion).put("recipes",JSONArray(recipes)).put("jobs",JSONArray(jobs)).put("grants",JSONArray(next.grants)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024)
        val out = metadata.startWrite()
        try { out.write(bytes); metadata.finishWrite(out);check(readMetadata().contentEquals(bytes));data = next } catch (e: Exception) { healthy=false;metadata.failWrite(out);throw e }
    }
    private fun readMetadata():ByteArray = metadata.openRead().use { input ->
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=1024*1024);out.write(buffer,0,n) };out.toByteArray()
    }
    private companion object { val ownedRoots=java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
}
