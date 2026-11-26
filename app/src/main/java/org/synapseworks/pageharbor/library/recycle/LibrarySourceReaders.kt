package org.synapseworks.pageharbor.library.recycle

import org.synapseworks.pageharbor.document.session.DocumentSession

/** Process-local pins for accepted sessions/history and captured readers. No filesystem IO. */
object LibrarySourceReaders {
    private data class Pins(val ids:Set<String>,val paths:Set<String>)
    private val owners=mutableMapOf<Any,Pins>()
    private val blocked=mutableMapOf<String,String>()
    private fun pins(sessions:Collection<DocumentSession>):Pins {
        val resources=sessions.flatMap{s->s.pages.map{it.source}+s.originalPdfSources+listOfNotNull(s.directPdfSource)}
        return Pins(sessions.mapNotNull{it.libraryDocument?.documentId}.toSet(),resources.mapNotNull{it.ownedLibraryFile?.path}.toSet())
    }
    @Synchronized fun retain(owner:Any,sessions:Collection<DocumentSession>){
        val p=pins(sessions);if(p.ids.isEmpty()&&p.paths.isEmpty())owners.remove(owner)else owners[owner]=p
    }
    /** Reserve before accepting an open result or capturing an operation; closes the check/pin race. */
    @Synchronized fun reserve(session:DocumentSession):Any? {
        if(!canCapture(session))return null
        return Any().also{owners[it]=pins(listOf(session))}
    }
    @Synchronized fun release(reservation:Any){owners.remove(reservation)}
    @Synchronized fun block(id:String,directory:String){blocked[id]=directory.trimEnd('/')+"/"}
    @Synchronized fun unblock(id:String){blocked.remove(id)}
    @Synchronized fun canCapture(session:DocumentSession):Boolean {
        val p=pins(listOf(session))
        return p.ids.none{it in blocked}&&p.paths.none{path->blocked.values.any{path.startsWith(it)}}
    }
    @Synchronized fun inUse(id:String,directory:String):Boolean {
        val prefix=directory.trimEnd('/')+"/"
        return owners.values.any{p->id in p.ids||p.paths.any{it.startsWith(prefix)}}
    }
}
