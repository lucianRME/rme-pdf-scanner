package org.synapseworks.pageharbor.integrity
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.synapseworks.pageharbor.workflow.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
internal class IntegrityWork(val directory:File){val finished=AtomicBoolean(false)}
internal class IntegrityInput(val file:File,val uri:Uri,val guard:WorkflowSourceGuard,val sha256:String,val bytes:Long){override fun toString()="IntegrityInput(<redacted>)"}
internal class IntegrityStore(private val context:Context){
 companion object{private val permit=Semaphore(1);private val monitor=Any();private val initialized=mutableSetOf<String>()}
 private val root=File(context.cacheDir,"integrity-review")
 suspend fun begin():IntegrityWork{var made:IntegrityWork?=null;var handed=false
  try{val result=withContext(Dispatchers.IO){permit.acquire();try{synchronized(monitor){check(root.mkdirs()||root.isDirectory);check(!java.nio.file.Files.isSymbolicLink(root.toPath()));if(initialized.add(root.canonicalPath))root.listFiles().orEmpty().filter{runCatching{UUID.fromString(it.name)}.isSuccess&&!java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach{it.deleteRecursively()};require(root.usableSpace>=160L*1024*1024);IntegrityWork(File(root,UUID.randomUUID().toString()).apply{check(mkdir())}).also{made=it}}}catch(t:Throwable){permit.release();throw t}};handed=true;return result
  }finally{if(!handed)withContext(NonCancellable+Dispatchers.IO){made?.let(::finish)}}
 }
 fun finish(w:IntegrityWork){if(w.finished.compareAndSet(false,true))try{w.directory.deleteRecursively()}finally{permit.release()}}
 suspend fun capture(w:IntegrityWork,uri:Uri,check:()->Unit):IntegrityInput=withContext(Dispatchers.IO){require(!w.finished.get()&&uri.scheme in setOf("file","content"));check();val guard=workflowSourceGuard(context,uri);val f=File(w.directory,"input-${UUID.randomUUID()}.pdf");var ok=false
  try{val pair=copy(uri,f,check);f.inputStream().use{val h=ByteArray(8);require(it.read(h)==8&&String(h,Charsets.ISO_8859_1).matches(Regex("%PDF-1\\.[0-7]")))};check();IntegrityInput(f,uri,guard,pair.first,pair.second).also{ok=true}}finally{if(!ok)f.delete()}}
 private suspend fun copy(uri:Uri,file:File?,check:()->Unit):Pair<String,Long>{val md=MessageDigest.getInstance("SHA-256");var total=0L;context.contentResolver.openInputStream(uri)!!.use{input->val out=file?.outputStream();try{val buffer=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();check();val n=input.read(buffer);if(n<0)break;require(n>0);total+=n;require(total<=IntegrityRules.INPUT);md.update(buffer,0,n);out?.write(buffer,0,n)};require(total>0)}finally{out?.close()}};return md.digest().joinToString(""){"%02x".format(it)}to total}
 suspend fun verify(input:IntegrityInput,check:()->Unit){require(copy(input.uri,null,check).first==input.sha256)}
}
