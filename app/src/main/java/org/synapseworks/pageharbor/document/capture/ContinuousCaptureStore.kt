package org.synapseworks.pageharbor.document.capture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Flat private files only. All operations run on the caller's serialized I/O worker. */
internal class ContinuousCaptureStore(val root:File) {
    companion object {
        private val startupLock=Any()
        private val initializedRoots=mutableSetOf<String>()
        const val MAX_BYTES=16L*1024*1024
        const val MIN_FREE=32L*1024*1024
    }
    fun cleanupOrphans() = synchronized(startupLock) {
        check(root.mkdirs()||root.isDirectory)
        check(!Files.isSymbolicLink(root.toPath()))
        if(initializedRoots.add(root.canonicalPath))root.listFiles()?.filter{validOwner(it.name)}?.forEach(::deleteRun)
    }
    fun create(ticket:CaptureTicket):File = synchronized(startupLock) {
        checkOwner(ticket.owner)
        cleanupOrphans()
        if(root.usableSpace<MIN_FREE)throw IOException()
        val run=File(root,ticket.owner);check(run.mkdirs()||run.isDirectory)
        check(run.canonicalFile.parentFile==root.canonicalFile&&!Files.isSymbolicLink(run.toPath()))
        File(run,"${ticket.sequence}.pending.jpg").also{check(!it.exists())}
    }
    fun ready(file:File):File {
        checkOwned(file);check(file.isFile&&file.length() in 1..MAX_BYTES)
        val ready=File(file.parentFile,file.name.replace(".pending.jpg",".jpg"));check(!ready.exists()&&file.renameTo(ready));return ready
    }
    fun delete(file:File) {checkOwned(file);file.delete();file.parentFile?.delete()}
    fun discard(owner:String) {checkOwner(owner);deleteRun(File(root,owner))}
    private fun deleteRun(run:File) {
        if(!validOwner(run.name)||Files.isSymbolicLink(run.toPath())||run.canonicalFile.parentFile!=root.canonicalFile)return
        run.listFiles()?.forEach{file->if(file.isFile&&!Files.isSymbolicLink(file.toPath())&&file.canonicalFile.parentFile==run.canonicalFile)file.delete()}
        run.delete()
    }
    private fun checkOwned(file:File) {
        val run=requireNotNull(file.parentFile);checkOwner(run.name)
        check(!Files.isSymbolicLink(file.toPath())&&!Files.isSymbolicLink(run.toPath()))
        check(file.canonicalFile.parentFile==run.canonicalFile&&run.canonicalFile.parentFile==root.canonicalFile)
    }
    private fun checkOwner(owner:String){require(validOwner(owner))}
    private fun validOwner(value:String):Boolean=runCatching{UUID.fromString(value).toString()==value}.getOrDefault(false)
}
