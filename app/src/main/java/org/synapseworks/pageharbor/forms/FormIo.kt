package org.synapseworks.pageharbor.forms

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FormIdentity(val device:Long,val inode:Long)
/** One physical operation, including cleanup, across all form ViewModels. */
internal object FormWork {
    private val mutex=Mutex();private val cleanup=ConcurrentLinkedQueue<()->Unit>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val active=mutableSetOf<File>()
    suspend fun <T> run(block:()->T):T=mutex.withLock{drain();try{block()}finally{drain()}}
    private fun drain(){while(true){val action=cleanup.poll()?:return;try{action()}catch(_:Exception){/* Retry orphan cleanup on next use; never log provider data. */}}}
    fun later(action:()->Unit){cleanup.add(action);scope.launch{run{}}}
    fun register(dir:File){if(active.isNotEmpty())throw FormException(FormFailure.LIMIT);active+=dir}
    fun remove(dir:File){active.remove(dir)}
    fun orphans(context:Context){val root=File(context.cacheDir,"forms");root.listFiles().orEmpty().filter{it.name.startsWith("session-")&&it !in active}.forEach{it.deleteRecursively()}}
}
internal class FormHandle private constructor(val dir:File,val original:Uri,private val descriptor:ParcelFileDescriptor,
    val identity:FormIdentity,val fingerprint:ByteArray,private val openOriginal:()->ParcelFileDescriptor?){
    val input=File(dir,"input.pdf");val scratch=File(dir,"scratch")
    private var refs=1;private var retired=false
    @Synchronized fun lease():AutoCloseable?{if(retired)return null;refs++;return AutoCloseable{release()}}
    @Synchronized fun close(){if(!retired){retired=true;release()}}
    @Synchronized private fun release(){if(--refs==0)FormWork.later{try{runCatching{descriptor.close()};runCatching{dir.deleteRecursively()}}finally{FormWork.remove(dir)}}}
    fun drop(file:File?){if(file!=null)FormWork.later{if(file.parentFile==dir&&file.name.startsWith("output-"))file.delete()}}
    fun unchanged(check:()->Unit):Boolean {
        check()
        if(identity(descriptor)!=identity||!MessageDigest.isEqual(fingerprint,digest(descriptor,check)))return false
        return try{val current=openOriginal()?:return false;current.use{check();identity(it)==identity&&MessageDigest.isEqual(fingerprint,digest(it,check))}}
        catch(e:CancellationException){throw e}catch(_:Exception){false}
    }
    fun output()=File(dir,"output-${UUID.randomUUID()}.pdf")
    override fun toString()="FormHandle(<redacted>)"
    companion object {
        fun capture(context:Context,uri:Uri,check:()->Unit):FormHandle {
            check();val root=File(context.cacheDir,"forms").apply{mkdirs()};FormWork.orphans(context)
            val dir=File(root,"session-${UUID.randomUUID()}");FormWork.register(dir)
            var fd:ParcelFileDescriptor?=null
            try {
                if(!dir.mkdir()||dir.usableSpace<64L*1024*1024)throw FormException(FormFailure.LIMIT)
                File(dir,"scratch").mkdir()
                fd=context.contentResolver.openFileDescriptor(uri,"r")?:throw FormException(FormFailure.MALFORMED)
                val id=try{identity(fd)}catch(_:FormException){throw FormException(FormFailure.SOURCE)};val size=Os.fstat(fd.fileDescriptor).st_size
                if(size !in 1..FormLimits.INPUT)throw FormException(FormFailure.LIMIT)
                val hash=MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use{input->
                    Os.lseek(input.fd,0,OsConstants.SEEK_SET)
                    FileOutputStream(File(dir,"input.pdf")).use{out->val buffer=ByteArray(16384);var total=0
                        while(true){check();val count=input.read(buffer);if(count<0)break;if(count==0||total.toLong()+count>FormLimits.INPUT)throw FormException(FormFailure.LIMIT);out.write(buffer,0,count);hash.update(buffer,0,count);total+=count}
                        if(total!=size.toInt())throw FormException(FormFailure.MALFORMED)
                    }
                }
                check();return FormHandle(dir,uri,fd,id,hash.digest()){context.contentResolver.openFileDescriptor(uri,"r")}
            }catch(e:Throwable){runCatching{fd?.close()};try{runCatching{dir.deleteRecursively()}}finally{FormWork.remove(dir)};throw e}
        }
        fun identity(fd:ParcelFileDescriptor):FormIdentity {val s=Os.fstat(fd.fileDescriptor);if(!OsConstants.S_ISREG(s.st_mode)||s.st_ino<=0)throw FormException(FormFailure.DESTINATION);Os.lseek(fd.fileDescriptor,0,OsConstants.SEEK_CUR);return FormIdentity(s.st_dev,s.st_ino)}
        private fun digest(fd:ParcelFileDescriptor,check:()->Unit):ByteArray{
            val s=Os.fstat(fd.fileDescriptor);if(s.st_size !in 1..FormLimits.INPUT)throw FormException(FormFailure.MALFORMED)
            val d=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(16384);var offset=0L
            while(offset<s.st_size){check();val count=Os.pread(fd.fileDescriptor,buffer,0,minOf(buffer.size.toLong(),s.st_size-offset).toInt(),offset);if(count<=0)throw FormException(FormFailure.MALFORMED);d.update(buffer,0,count);offset+=count};return d.digest()
        }
    }
}
internal object FormDestination {
    private val authorities=setOf("com.android.externalstorage.documents","com.android.providers.downloads.documents")
    fun supports(context:Context,uri:Uri):Boolean {
        if(uri.scheme!="content"||uri.authority !in authorities)return false
        val provider=context.packageManager.resolveContentProvider(uri.authority!!,0)?:return false
        return provider.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0
    }
    /** OpenTarget must honor rw (no truncation); only trusted system-local providers are used by UI. */
    fun write(source:FormHandle,output:File,uri:Uri,openTarget:()->ParcelFileDescriptor?,check:()->Unit):Boolean {
        check();if(uri==source.original)throw FormException(FormFailure.DESTINATION);if(!source.unchanged(check))throw FormException(FormFailure.SOURCE_CHANGED)
        if(!output.isFile||output.length() !in 1..FormLimits.OUTPUT)throw FormException(FormFailure.LIMIT)
        val target=openTarget()?:throw FormException(FormFailure.DESTINATION);var handed=false
        try {
            check();val id=FormHandle.identity(target)
            if(id==source.identity||Os.fstat(target.fileDescriptor).st_size!=0L)throw FormException(FormFailure.DESTINATION)
            Os.lseek(target.fileDescriptor,0,OsConstants.SEEK_SET);check();if(!source.unchanged(check))throw FormException(FormFailure.SOURCE_CHANGED)
            handed=true
            ParcelFileDescriptor.AutoCloseOutputStream(target).use{out->output.inputStream().use{input->val buffer=ByteArray(8192);var total=0
                while(true){check();val count=input.read(buffer);if(count<0)break;if(count==0||total.toLong()+count>FormLimits.OUTPUT)throw FormException(FormFailure.LIMIT);out.write(buffer,0,count);total+=count}
                check();out.flush()
            }}
            check();if(!source.unchanged(check))throw FormException(FormFailure.SOURCE_CHANGED);return true
        }finally{if(!handed)target.close()}
    }
}
