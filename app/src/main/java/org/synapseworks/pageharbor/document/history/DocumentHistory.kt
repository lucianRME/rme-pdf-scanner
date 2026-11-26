package org.synapseworks.pageharbor.document.history

import org.synapseworks.pageharbor.document.session.*
import java.security.MessageDigest

enum class HistoryAction { ROTATE, FILTER, ADJUST, BATCH, REORDER, DELETE, APPEND, BOOK_SPLIT, CURVATURE, CROP, ANNOTATE, COLLAGE, OBJECT_REMOVAL, FINGER_REMOVAL }
internal data class HistoryLimits(val entries:Int=32,val metadataBytes:Long=4L*1024*1024,val temporaryBytes:Long=256L*1024*1024,val pages:Int=500,val validationBytes:Long=512L*1024*1024)
internal data class HistoryEntry(val before:DocumentSession,val after:DocumentSession,val action:HistoryAction,val provenance:DocumentSession?=null)
internal data class HistoryMove(val version:Long,val expected:DocumentSession,val target:DocumentSession,val undo:Boolean,val action:HistoryAction)
data class HistoryUiState(val undoCount:Int=0,val redoCount:Int=0,val undoAction:HistoryAction?=null,val redoAction:HistoryAction?=null,
    val busy:Boolean=false,val dirty:Boolean=false,val limited:Boolean=false,val unavailable:Boolean=false,val retainedBytes:Long=0) {
    val canUndo get()=undoCount>0&&!busy
    val canRedo get()=redoCount>0&&!busy
}
/** Immutable metadata references only. No bitmap, password, OCR text, destination or filesystem IO. */
internal class DocumentHistory(val limits:HistoryLimits=HistoryLimits()) {
    private val entries=mutableListOf<HistoryEntry>();private var cursor=0;private var version=0L
    var present=DocumentSession();private set
    private var baseline=signature(present)
    private val baselineAliases=mutableSetOf<String>()
    var limited=false;private set
    val undoCount get()=cursor
    val redoCount get()=entries.size-cursor
    val undoAction get()=entries.getOrNull(cursor-1)?.action
    val redoAction get()=entries.getOrNull(cursor)?.action
    val dirty get()=signature(present).let{it!=baseline&&it !in baselineAliases}
    fun sessions():List<DocumentSession> = (listOf(present)+entries.flatMap{listOfNotNull(it.before,it.after,it.provenance)}).distinct()
    fun reset(session:DocumentSession):List<DocumentSession> {
        val retired=sessions();entries.clear();cursor=0;version++;present=session;baseline=signature(session);baselineAliases.clear();limited=false;return retired
    }
    fun record(before:DocumentSession,after:DocumentSession,action:HistoryAction,frozenBefore:DocumentSession?=null):List<DocumentSession> {
        require(before===present);if(after==before)return emptyList()
        val cleanBefore=!dirty
        val retired=entries.drop(cursor).flatMap{listOfNotNull(it.before,it.after,it.provenance)}.toMutableList()
        while(entries.size>cursor)entries.removeAt(entries.lastIndex)
        present=after;version++
        if(before.pages.size>limits.pages||after.pages.size>limits.pages){retired+=sessions();retired+=before;entries.clear();cursor=0;baselineAliases.clear();limited=true;return retired}
        val undo=frozenBefore?:before
        entries+=HistoryEntry(undo,after,action,if(frozenBefore!=null)before else null);cursor++
        if(frozenBefore!=null&&cleanBefore)baselineAliases+=signature(undo)
        pruneAliases()
        while(entries.size>limits.entries||metadataCharge()>limits.metadataBytes){retired+=evictOne()}
        pruneAliases();return retired
    }
    private fun pruneAliases(){if(baselineAliases.isNotEmpty())baselineAliases.retainAll((listOf(present)+entries.flatMap{listOf(it.before,it.after)}).map(::signature).toSet())}
    fun move(undo:Boolean):HistoryMove? {
        val e=if(undo)entries.getOrNull(cursor-1)else entries.getOrNull(cursor)
        return e?.let{HistoryMove(version,present,if(undo)it.before else it.after,undo,it.action)}
    }
    fun accept(request:HistoryMove):Boolean {
        if(request.version!=version||request.expected!==present)return false
        val target=move(request.undo)?:return false
        if(target.target!==request.target)return false
        cursor+=if(request.undo)-1 else 1;present=request.target;version++;return true
    }
    fun evictToByteLimit(bytes:(List<DocumentSession>)->Long):List<DocumentSession> {
        val retired=mutableListOf<DocumentSession>()
        while(entries.isNotEmpty()&&bytes(sessions())>limits.temporaryBytes)retired+=evictOne()
        pruneAliases();return retired
    }
    private fun evictOne():List<DocumentSession> {
        if(entries.isEmpty())return emptyList()
        limited=true;version++
        val e=if(cursor>0){cursor--;entries.removeAt(0)}else entries.removeAt(entries.lastIndex)
        pruneAliases();return listOfNotNull(e.before,e.after,e.provenance)
    }
    fun rebaseLibrary(reference:LibraryDocumentReference):List<DocumentSession> {
        present=present.copy(libraryDocument=reference)
        for(i in entries.indices){val e=entries[i];entries[i]=e.copy(before=e.before.copy(libraryDocument=reference),after=e.after.copy(libraryDocument=reference),provenance=e.provenance?.copy(libraryDocument=reference))}
        version++
        val retired=mutableListOf<DocumentSession>()
        while(entries.isNotEmpty()&&metadataCharge()>limits.metadataBytes)retired+=evictOne()
        return retired
    }
    fun metadataCharge():Long=baselineAliases.size*256L+sessions().filter{it!==present}.sumOf{state->
        fun resource(r:DocumentResource)=128L+2L*(r.reference.length.toLong()+(r.ownedTemporaryFile?.path?.length?:0)+(r.ownedTemporaryFile?.rootPath?.length?:0)+
            (r.ownedLibraryFile?.path?.length?:0)+(r.ownedLibraryFile?.rootPath?.length?:0))
        128L+state.pages.sumOf{p->256L+resource(p.source)+2L*(p.persistentId?.length?:0)+(if(p.annotations.isEmpty)0L else p.annotations.metadataBytes)+(if(p.paperSize==null)0L else 32L)}+
            (state.directPdfSource?.let(::resource)?:0L)+16L*state.directPdfPageIds.size+state.originalPdfSources.sumOf(::resource)+
            (state.libraryDocument?.let{128L+2L*(it.documentId.length+it.title.length+(it.folderId?.length?:0))}?:0L)
    }
    companion object {
        fun resources(states:List<DocumentSession>):List<DocumentResource> = states.flatMap{s->s.pages.map{it.source}+listOfNotNull(s.directPdfSource)+s.originalPdfSources}
            .distinctBy{key(it)}
        fun key(r:DocumentResource)=r.ownership.name+":"+(r.ownedTemporaryFile?.path?:r.ownedLibraryFile?.path?:r.reference)
        private fun signature(s:DocumentSession):String {
            val d=MessageDigest.getInstance("SHA-256")
            fun add(value:Any?){val b=(value?.toString()?:"<null>").toByteArray(Charsets.UTF_8);val n=b.size;d.update(byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte()));d.update(b)}
            add(s.pages.size);s.pages.forEach{p->add(p.id.value);add(p.source);add(p.sourceCategory);add(p.contentType);add(p.imageMetadata);add(p.rotation);add(p.filter);add(p.adjustments);add(p.persistentId);if(!p.annotations.isEmpty)add(p.annotations.fingerprint);if(p.paperSize!=null)add(p.paperSize.encode())}
            add(s.directPdfSource);add(s.directPdfPageIds);s.originalPdfSources.forEach{add(it)}
            return d.digest().joinToString(""){"%02x".format(it)}
        }
    }
}
