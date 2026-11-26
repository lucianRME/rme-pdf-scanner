package org.synapseworks.pageharbor.workflow

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Serialized state machine; public IDs never carry names, URI values or document text. */
internal class WorkflowQueue(private val context: Context, val store: WorkflowStore) {
    val destination = WorkflowDestination(context)
    private val writer = Mutex()
    @Volatile var active = false
    @Volatile private var authorityEpoch=0L
    var changed: () -> Unit = {}
    private var manual: Pair<String,String>? = null
    private val blockedRecipes=mutableSetOf<String>()
    private val stoppedJobs=mutableSetOf<String>()
    private val reviewedSourceGrants=mutableMapOf<String,Int>()
    init { if(store.healthy)runCatching { releaseUnusedGrants() }.onFailure { store.disable() } }
    @Synchronized fun chooseTree(uri:Uri):Boolean {
        val previously=context.contentResolver.persistedUriPermissions.any { it.uri==uri }
        if(!destination.take(uri))return false
        if(!previously) {
            try { require(store.data.grants.size<32);commit(store.data.copy(grants=(store.data.grants+uri.toString()).distinct())) }
            catch(e:Exception) { destination.release(uri.toString());throw e }
        }
        return true
    }
    @Synchronized fun releaseUnusedGrants() {
        val used=store.data.recipes.mapNotNull { it.tree }.toSet()+store.data.jobs.filter { it.status.pending() }.mapNotNull { it.tree }+store.data.jobs.mapNotNull { it.destination }+reviewedSourceGrants.keys+store.data.jobs.filter { it.status.pending() && it.preset==WorkflowPreset.OFFICE }.flatMap { it.sourceGuards.map { g->g.uri } }
        val unused=store.data.grants.filter { it !in used };unused.forEach(destination::release)
        val released=unused.filter { uri->context.contentResolver.persistedUriPermissions.none { it.uri.toString()==uri && (it.isReadPermission || android.provider.DocumentsContract.isTreeUri(it.uri) && it.isWritePermission) } }
        if(released.isNotEmpty())commit(store.data.copy(grants=store.data.grants-released.toSet()))
    }
    @Synchronized fun reserveOfficeSource(guard:WorkflowSourceGuard,reviewOnly:Boolean=false) {
        check((active || reviewOnly) && store.healthy)
        val uri=Uri.parse(guard.uri)
        requireNotNull(context.contentResolver.openFileDescriptor(uri,"r")).use { require(destination.localSource(uri,it)) }
        val borrowed=context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission }
        reviewedSourceGrants[guard.uri]=(reviewedSourceGrants[guard.uri]?:0)+1
        try {
            if(!borrowed) {
                require(store.data.grants.size<32)
                // Journal intent BEFORE acquiring a grant; restart can safely release it.
                commit(store.data.copy(grants=(store.data.grants+guard.uri).distinct()))
                context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            require(destination.sourceGranted(guard))
        } catch(failure:Exception) { releaseOfficeSource(guard.uri);throw failure }
    }
    @Synchronized fun releaseOfficeSource(uri:String) { val remaining=(reviewedSourceGrants[uri]?:1)-1;if(remaining<=0)reviewedSourceGrants.remove(uri)else reviewedSourceGrants[uri]=remaining;releaseUnusedGrants() }
    @Synchronized fun data()=store.data
    @Synchronized private fun commit(data: WorkflowData) { store.commit(data);changed() }
    @Synchronized fun saveRecipe(recipe: WorkflowRecipe) {
        require(recipe.label.length in 1..60 && recipe.preset.available)
        WorkflowNaming.name(recipe.template,recipe.custom,java.time.Instant.EPOCH,java.time.ZoneId.of("UTC"),1,recipe.preset.extension)
        require(recipe.tree==null || recipe.treeIdentity!=null && destination.localTree(recipe.tree,recipe.treeIdentity))
        val old=store.data.recipes.firstOrNull { it.id==recipe.id }
        require(old!=null || store.data.recipes.size<16)
        if(old!=null) {
            blockedRecipes+=recipe.id
            stoppedJobs+=store.data.jobs.filter { it.recipeId==recipe.id && it.status==WorkflowStatus.WRITING }.map { it.id }
        }
        val next=recipe.copy(version=(old?.version?.plus(1)?:1),enabled=false,nextCounter=old?.nextCounter?:1)
        commit(store.data.copy(recipes=store.data.recipes.filterNot { it.id==recipe.id }+next,jobs=store.data.jobs.map { if(it.recipeId==recipe.id && it.status.pending() && it.status!=WorkflowStatus.UNCERTAIN)it.copy(status=if(it.status in setOf(WorkflowStatus.WRITING,WorkflowStatus.PICKER))WorkflowStatus.UNCERTAIN else WorkflowStatus.PAUSED) else it }))
    }
    @Synchronized fun enable(id: String, enabled: Boolean) {
        require(store.data.recipes.any { it.id==id })
        if(!enabled) { blockedRecipes+=id;stoppedJobs+=store.data.jobs.filter { it.recipeId==id && it.status==WorkflowStatus.WRITING }.map { it.id } }
        require(!enabled || store.data.recipes.any { it.id==id && it.preset.available })
        require(!enabled || store.data.jobs.any { it.recipeId==id && it.status.pending() })
        commit(store.data.copy(recipes=store.data.recipes.map { if(it.id==id)it.copy(enabled=enabled)else it },jobs=store.data.jobs.map { if(it.recipeId==id && !enabled && it.status.pending() && it.status!=WorkflowStatus.UNCERTAIN) it.copy(status=if(it.status in setOf(WorkflowStatus.WRITING,WorkflowStatus.PICKER))WorkflowStatus.UNCERTAIN else WorkflowStatus.PAUSED) else it }))
        if(enabled)blockedRecipes-=id else manual=null
    }
    @Synchronized fun revoke(id: String) {
        authorityEpoch++
        val trees=(store.data.recipes.filter { it.id==id }.mapNotNull { it.tree }+store.data.jobs.filter { it.recipeId==id && it.status.pending() }.mapNotNull { it.tree }).distinct()
        stoppedJobs+=store.data.jobs.filter { it.recipeId==id && it.status.pending() }.map { it.id }
        try { enable(id,false) } finally { manual=null;trees.forEach(destination::release) }
        // Old immutable jobs retain the revoked identity; they cannot inherit a later grant/recipe.
        commit(store.data.copy(jobs=store.data.jobs.map { if(it.recipeId==id && it.status.pending() && it.status!=WorkflowStatus.UNCERTAIN)it.copy(status=WorkflowStatus.CANCELLED)else it }))
        cleanup();releaseUnusedGrants()
    }
    @Synchronized fun deleteRecipe(id: String) { require(store.data.jobs.none { it.recipeId==id && it.status.pending() });commit(store.data.copy(recipes=store.data.recipes.filterNot { it.id==id }));blockedRecipes-=id;releaseUnusedGrants() }
    @Synchronized fun enqueue(recipe: WorkflowRecipe, captured: File, digest: String, bytes: Long, name: String, revision: Long, owner: String, key: String, at: String, zone: String, sourceGuards:List<WorkflowSourceGuard> = emptyList()): WorkflowJob {
        check(active && store.healthy)
        store.data.jobs.firstOrNull { it.duplicateKey==key }?.let { return it }
        require(sourceGuards.size<=40 && (!recipe.preset.excludesSources || sourceGuards.isNotEmpty()))
        require(recipe.preset!=WorkflowPreset.OFFICE || recipe.preset.available && sourceGuards.size==1 && sourceGuards.all(destination::sourceGranted))
        val current=store.data.recipes.first { it.id==recipe.id };require(current==recipe)
        require(name==WorkflowNaming.name(current.template,current.custom,java.time.Instant.parse(at),java.time.ZoneId.of(zone),current.nextCounter,current.preset.extension))
        require(store.data.jobs.size<16 && store.data.jobs.count { it.status.pending() }<8 && current.nextCounter<=999999)
        require(captured.isFile && captured.canonicalFile.toPath().startsWith(store.root.canonicalFile.toPath()))
        require(bytes in 1..(if(recipe.preset==WorkflowPreset.XLSX)org.synapseworks.pageharbor.xlsx.XLSX_MAX else WORKFLOW_MAX_PDF) && ownedBytes()+bytes<=WORKFLOW_MAX_OWNED)
        require(workflowDigest(captured.inputStream())==digest to bytes)
        val id=UUID.randomUUID().toString();val pdf=store.pdf(id)
        check(captured.renameTo(pdf))
        val job=WorkflowJob(id,recipe.id,recipe.version,name,recipe.preset,recipe.tree,revision,owner,key,at,zone,recipe.nextCounter,digest,bytes,treeIdentity=recipe.treeIdentity,sourceGuards=sourceGuards.toList())
        try { commit(store.data.copy(recipes=store.data.recipes.map { if(it.id==recipe.id)it.copy(nextCounter=it.nextCounter+1)else it },jobs=store.data.jobs+job)) } catch(e:Exception){pdf.delete();throw e}
        return job
    }
    @Synchronized fun status(id: String, status: WorkflowStatus, target: String?=null) {
        commit(store.data.copy(jobs=store.data.jobs.map { if(it.id==id)it.copy(status=status,destination=target?:it.destination)else it }))
    }
    @Synchronized fun cancel(id: String) {
        val job=store.data.jobs.firstOrNull { it.id==id }?:return
        if(job.status==WorkflowStatus.SAVED)return
        stoppedJobs+=id
        status(id,if(job.status in setOf(WorkflowStatus.WRITING,WorkflowStatus.PICKER) || job.destination!=null)WorkflowStatus.UNCERTAIN else WorkflowStatus.CANCELLED)
        if(manual?.first==id)manual=null
        cleanup()
    }
    @Synchronized fun keepExternalAndCancel(id: String) { status(id,WorkflowStatus.CANCELLED);cleanup() }
    @Synchronized fun remove(id: String) { require(store.data.jobs.none { it.id==id && it.status.pending() });commit(store.data.copy(jobs=store.data.jobs.filterNot { it.id==id }));store.pdf(id).delete();stoppedJobs-=id;releaseUnusedGrants() }
    @Synchronized fun suspendWrites() { authorityEpoch++;active=false;stoppedJobs+=store.data.jobs.filter { it.status==WorkflowStatus.WRITING }.map { it.id };commit(store.data.copy(jobs=store.data.jobs.map { if(it.status==WorkflowStatus.WRITING)it.copy(status=WorkflowStatus.UNCERTAIN)else it })) }
    private fun officeSourceAvailable(job:WorkflowJob)=job.preset!=WorkflowPreset.OFFICE || job.sourceGuards.size==1 && job.sourceGuards.all(destination::sourceGranted)
    @Synchronized private fun permitted(job: WorkflowJob): Boolean = active && job.preset.available && store.healthy && job.recipeId !in blockedRecipes && job.id !in stoppedJobs && store.data.recipes.any { it.id==job.recipeId && it.enabled } && store.data.jobs.any { it.id==job.id && it.status==WorkflowStatus.WRITING } && (job.tree==null || job.treeIdentity!=null && destination.localTree(job.tree,job.treeIdentity)) && officeSourceAvailable(job)
    @Synchronized fun manualRequest(id: String): String? {
        val job=store.data.jobs.firstOrNull { it.id==id && it.preset.available && it.tree==null && it.status in setOf(WorkflowStatus.PAUSED,WorkflowStatus.MANUAL_REQUIRED,WorkflowStatus.FAILED) }?:return null
        if(!active || manual!=null || job.id in stoppedJobs || job.recipeId in blockedRecipes || store.data.recipes.none { it.id==job.recipeId && it.enabled })return null
        val token=UUID.randomUUID().toString();status(id,WorkflowStatus.PICKER);manual=id to token;return token
    }
    @Synchronized fun invalidateManual() { manual?.first?.let { id -> if(store.data.jobs.any { it.id==id && it.status==WorkflowStatus.PICKER })status(id,WorkflowStatus.UNCERTAIN) };manual=null }
    @Synchronized fun consumeManual(token: String, uri: Uri?): String? {
        val request=manual?.takeIf { it.second==token }?:return null;manual=null
        val job=store.data.jobs.firstOrNull { it.id==request.first }?:return null
        if(uri==null) { if(job.status==WorkflowStatus.PICKER)status(job.id,WorkflowStatus.PAUSED);return null }
        if(!job.preset.available || !active || job.id in stoppedJobs || job.recipeId in blockedRecipes || job.status!=WorkflowStatus.PICKER) { status(job.id,WorkflowStatus.UNCERTAIN,uri.toString());return null }
        val previous=context.contentResolver.persistedUriPermissions.any { it.uri==uri }
        if(!previous) {
            val persisted=runCatching { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);true }.getOrDefault(false)
            if(persisted) {
                try { require(store.data.grants.size<32);commit(store.data.copy(grants=(store.data.grants+uri.toString()).distinct())) }
                catch(e:Exception) { destination.release(uri.toString());status(job.id,WorkflowStatus.UNCERTAIN,uri.toString());return null }
            }
        }
        status(job.id,WorkflowStatus.PICKER,uri.toString())
        return request.first
    }
    suspend fun run(id: String, manualTarget: Uri?=null) = writer.withLock {
        val job=synchronized(this) { store.data.jobs.firstOrNull { it.id==id } }?:return@withLock
        if(!job.preset.available)return@withLock
        val manualPicker=job.status==WorkflowStatus.PICKER && manualTarget?.toString()==job.destination
        if(!manualPicker && job.status !in setOf(WorkflowStatus.PAUSED,WorkflowStatus.QUEUED,WorkflowStatus.FAILED,WorkflowStatus.MANUAL_REQUIRED,WorkflowStatus.COLLISION))return@withLock
        if(job.destination!=null && !manualPicker) { status(id,WorkflowStatus.UNCERTAIN);return@withLock }
        if(synchronized(this){job.recipeId in blockedRecipes || job.id in stoppedJobs} || !active || data().recipes.none { it.id==job.recipeId && it.enabled }) { status(id,if(job.destination!=null)WorkflowStatus.UNCERTAIN else WorkflowStatus.PAUSED);return@withLock }
        var creating=false
        try {
            currentCoroutineContext().ensureActive()
            require(active && data().recipes.any { it.id==job.recipeId && it.enabled })
            require(officeSourceAvailable(job))
            require(workflowDigest(store.pdf(id).inputStream())==job.digest to job.bytes)
            if(job.tree==null && manualTarget==null) { status(id,WorkflowStatus.MANUAL_REQUIRED);return@withLock }
            if(job.tree!=null) { require(job.treeIdentity!=null && destination.localTree(job.tree,job.treeIdentity));if(destination.collision(job.tree,job.name)) { status(id,WorkflowStatus.COLLISION);return@withLock } }
            status(id,WorkflowStatus.WRITING)
            val target=synchronized(this) { check(permitted(job));creating=true;manualTarget?.toString()?:destination.create(requireNotNull(job.tree),job.name,job.preset.mime) }
            synchronized(this) {
                if(permitted(job)) status(id,WorkflowStatus.WRITING,target)
                else { status(id,WorkflowStatus.UNCERTAIN,target);error("Interrupted") }
            }
            check(permitted(job) && destination.validateTarget(target,job.name,job.tree,true,job.preset.mime))
            store.pdf(id).inputStream().use { input ->
                check(permitted(job))
                val descriptor=requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(target),"rw"))
                try {
                    check(descriptor.statSize==0L && (job.tree==null || destination.descriptorLocal(descriptor,target)))
                    if(job.preset.excludesSources)check(workflowDifferentSources(context,descriptor,Uri.parse(target),job.sourceGuards))
                    android.system.Os.lseek(descriptor.fileDescriptor,0,android.system.OsConstants.SEEK_SET)
                }
                catch(e:Exception) { descriptor.close();throw e }
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        synchronized(this) { check(permitted(job) && (job.tree==null || destination.descriptorLocal(descriptor,target)));if(job.preset==WorkflowPreset.OFFICE)check(workflowDifferentSources(context,descriptor,Uri.parse(target),job.sourceGuards));output.write(buffer,0,n) }
                    }
                    synchronized(this) { check(permitted(job));output.flush() }
                }
            }
            check(permitted(job) && destination.validateTarget(target,job.name,job.tree,false,job.preset.mime))
            val actual=workflowDigest(requireNotNull(context.contentResolver.openInputStream(Uri.parse(target))))
            synchronized(this) { check(permitted(job));require(actual==job.digest to job.bytes);status(id,WorkflowStatus.SAVED);cleanup() }
        } catch (_: Exception) {
            synchronized(this) {
                val current=store.data.jobs.firstOrNull { it.id==id }
                if(current!=null && current.status!=WorkflowStatus.CANCELLED && current.status!=WorkflowStatus.SAVED) status(id,if(creating || current.destination!=null)WorkflowStatus.UNCERTAIN else WorkflowStatus.FAILED)
            }
        }
    }
    suspend fun reconcile(id: String) = writer.withLock {
        val job=data().jobs.firstOrNull { it.id==id && it.status==WorkflowStatus.UNCERTAIN }?:return@withLock
        if(!active || job.destination==null)return@withLock
        val epoch=authorityEpoch;val operation=currentCoroutineContext()
        val check:()->Unit={operation.ensureActive();check(active && epoch==authorityEpoch && data().jobs.any { it.id==id && it.status==WorkflowStatus.UNCERTAIN })}
        fun targetMatches()=(job.tree==null || job.treeIdentity!=null && destination.localTree(job.tree,job.treeIdentity)) && destination.validateTarget(job.destination,job.name,job.tree,false,job.preset.mime)
        if(!targetMatches())return@withLock
        val snapshot=runCatching { workflowDigest(store.pdf(id).inputStream(),check=check) }.getOrNull()
        if(snapshot!=job.digest to job.bytes)return@withLock
        val actual=runCatching { workflowDigest(requireNotNull(context.contentResolver.openInputStream(Uri.parse(job.destination))),check=check) }.getOrNull()
        if(actual==job.digest to job.bytes && targetMatches())synchronized(this) { check();status(id,WorkflowStatus.SAVED);cleanup() }
    }
    fun closeOwnerIfIdle():Boolean { active=false;if(!writer.tryLock())return false;try { store.close() } finally { writer.unlock() };return true }
    suspend fun closeOwner() { active=false;writer.withLock { store.close() } }
    @Synchronized private fun cleanup() { store.data.jobs.filterNot { it.status.pending() }.forEach { store.pdf(it.id).delete() } }
    fun ownedBytes()=store.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
