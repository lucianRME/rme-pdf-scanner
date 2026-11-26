package org.synapseworks.pageharbor.document.history

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.synapseworks.pageharbor.document.session.*

internal fun interface HistorySourceAccess { fun open(resource:DocumentResource):InputStream }
internal class AndroidHistorySourceAccess(context:Context):HistorySourceAccess {
    private val resolver=context.applicationContext.contentResolver
    override fun open(resource:DocumentResource)=requireNotNull(resolver.openInputStream(resource.toAndroidUri()))
}
internal data class HistoryAudit(val sizes:Map<String,Long>,val identities:Map<String,String>,val available:Boolean)
/** One IO mutex and a conflated cleanup signal; no queued bitmap/file copies or Main-thread IO. */
internal class HistoryResources(private val access:HistorySourceAccess,private val limits:HistoryLimits,
    private val delete:(List<DocumentResource>,List<DocumentResource>)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex();private val lock=Any();private val signal=Channel<Unit>(Channel.CONFLATED)
    private var retained=emptyList<DocumentResource>();private var transient=emptyList<DocumentResource>();private var epoch=0L
    private val candidates=linkedMapOf<String,DocumentResource>()
    private val hashes=mutableMapOf<String,String>()
    private var fingerprintEpoch=0L;private var admittedEpoch=0L
    fun resetAdmission(){synchronized(lock){fingerprintEpoch++}}
    private fun resetHashesIfNeeded(){val requested=synchronized(lock){fingerprintEpoch};if(requested!=admittedEpoch){hashes.clear();admittedEpoch=requested}}
    init{scope.launch{try{for(ignored in signal)mutex.withLock{runCatching{collectUnreachable()}}}finally{scope.cancel()}}}
    fun retain(states:List<DocumentSession>,pending:List<DocumentResource> = emptyList()) {
        val resources=DocumentHistory.resources(states)+pending
        synchronized(lock){retained=resources;transient=emptyList();epoch++};signal.trySend(Unit)
    }
    fun cleanup(resources:List<DocumentResource>,preserving:List<DocumentResource>) {
        synchronized(lock){resources.filter{it.ownership==DocumentResourceOwnership.RME_OWNED_TEMPORARY}.forEach{candidates[DocumentHistory.key(it)]=it};transient=transient+preserving;epoch++}
        signal.trySend(Unit)
    }
    suspend fun audit(states:List<DocumentSession>):HistoryAudit=withContext(Dispatchers.IO){mutex.withLock{
        resetHashesIfNeeded()
        val resources=DocumentHistory.resources(states);val sizes=mutableMapOf<String,Long>();val identities=mutableMapOf<String,String>();var available=true;var bytes=0L
        for(r in resources){
            ensureActive();val key=identity(r);identities[DocumentHistory.key(r)]=key
            if(r.ownership==DocumentResourceOwnership.RME_OWNED_TEMPORARY){val f=r.ownedTemporaryFile?.canonicalIdentityOrNull()?.file;sizes[key]=f?.takeIf{it.isFile}?.length()?:0L}
        }
        for(r in resources){
            ensureActive();val key=identities.getValue(DocumentHistory.key(r))
            if(key !in hashes){try{val pair=fingerprint(r,limits.validationBytes-bytes);bytes+=pair.second;hashes[key]=pair.first}catch(e:CancellationException){throw e}catch(_:Exception){available=false;break}}
        };HistoryAudit(sizes,identities,available)
    }}
    suspend fun validate(target:DocumentSession):Boolean=withContext(Dispatchers.IO){mutex.withLock{
        resetHashesIfNeeded()
        if(target.pages.size>limits.pages)return@withLock false
        var bytes=0L
        try{for(r in DocumentHistory.resources(listOf(target))){ensureActive();val expected=hashes[identity(r)]?:return@withLock false
            val pair=fingerprint(r,limits.validationBytes-bytes);bytes+=pair.second;if(pair.first!=expected)return@withLock false};true
        }catch(e:CancellationException){throw e}catch(_:Exception){false}
    }}
    suspend fun awaitIdle(){withContext(Dispatchers.IO){mutex.withLock{runCatching{collectUnreachable()}}}}
    fun extraBytes(states:List<DocumentSession>,protectedStates:List<DocumentSession>,audit:HistoryAudit):Long {
        // The IO audit supplied canonical identities; this metadata-only lookup is safe on Main.
        fun id(r:DocumentResource)=audit.identities[DocumentHistory.key(r)]?:DocumentHistory.key(r)
        val protected=DocumentHistory.resources(protectedStates).map(::id).toSet()
        return DocumentHistory.resources(states).filter{it.ownership==DocumentResourceOwnership.RME_OWNED_TEMPORARY}.map(::id).toSet().filterNot{it in protected}.sumOf{audit.sizes[it]?:0L}
    }
    fun close(){signal.close()}
    private suspend fun fingerprint(r:DocumentResource,maximum:Long):Pair<String,Long> {
        check(maximum>=0);val digest=MessageDigest.getInstance("SHA-256");var count=0L
        access.open(r).use{input->val buffer=ByteArray(64*1024)
            while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer,0,minOf(buffer.size.toLong(),maximum-count+1).coerceAtLeast(1).toInt());if(n<0)break;if(n==0)continue;count+=n;check(count<=maximum);digest.update(buffer,0,n)}}
        check(count>0);return digest.digest().joinToString(""){"%02x".format(it)}to count
    }
    private fun identity(r:DocumentResource):String = r.ownedTemporaryFile?.canonicalIdentityOrNull()?.path
        ?:r.ownedLibraryFile?.canonicalIdentityOrNull()?.path
        ?:runCatching{URI(r.reference).takeIf{it.scheme=="file"}?.let{File(it).canonicalPath}}.getOrNull()
        ?:"uri:"+r.reference
    private fun collectUnreachable() {
        val snapshot=synchronized(lock){Triple(epoch,retained+transient,candidates.toMap())}
        val paths=snapshot.second.map(::identity).toSet();val references=snapshot.second.map{it.reference}.toSet()
        for((key,r)in snapshot.third){
            if(identity(r)in paths||r.reference in references)continue
            if(synchronized(lock){epoch!=snapshot.first}){signal.trySend(Unit);return}
            if(r.ownedTemporaryFile?.canonicalIdentityOrNull()?.file?.isFile==true)delete(listOf(r),snapshot.second)
            if(r.ownedTemporaryFile?.canonicalIdentityOrNull()?.file?.exists()!=true)synchronized(lock){candidates.remove(key)}
        }
        val keep=snapshot.second.map(::identity).toSet();hashes.keys.retainAll(keep)
    }
}
