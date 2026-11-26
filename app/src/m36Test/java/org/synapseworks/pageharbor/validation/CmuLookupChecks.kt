package org.synapseworks.pageharbor.validation
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import org.synapseworks.pageharbor.resourcepack.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Synthetic test packages only; real pinned resource and production parser/leases. */
class CmuLookupChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val result=Bundle();val report=JSONObject();val root=File(targetContext.noBackupFilesDir,"cmu-measure-${UUID.randomUUID()}");val running=AtomicBoolean(true);var sampler:Thread?=null
 try {
  check(targetContext.packageName in setOf("org.synapseworks.pageharbor.m36packsmoke","org.synapseworks.pageharbor.m36packr8"))
  val store=ResourcePackStore(root);targetContext.assets.open("cmudict-en-us.zip").use{store.import(it)}
  val runtime=Runtime.getRuntime();fun javaBytes()=runtime.totalMemory()-runtime.freeMemory()
  val baseline=javaBytes();val baselineNative=Debug.getNativeHeapAllocatedSize();var peak=baseline;var peakNative=baselineNative;var peakPss=Debug.getPss().toLong()*1024
  sampler=Thread{while(running.get()){peak=maxOf(peak,javaBytes());peakNative=maxOf(peakNative,Debug.getNativeHeapAllocatedSize());peakPss=maxOf(peakPss,Debug.getPss().toLong()*1024);Thread.sleep(50)}}.apply{start()}
  val cases=JSONObject(targetContext.assets.open("expected.json").bufferedReader().use{it.readText()}).getJSONObject("cases");val timings=JSONArray();var loadMs=0L
  store.usePack(CmuLexicon.PACK_ID){p,d->val begin=SystemClock.elapsedRealtime();CmuLexicon.load(p,d){check(SystemClock.elapsedRealtime()-begin<=10000)}.use{lex->loadMs=SystemClock.elapsedRealtime()-begin;check(!store.remove(p.id));check(!ResourcePackStore(root).remove(p.id))
    val keys=cases.keys();while(keys.hasNext()){val q=keys.next();val expected=cases.getJSONArray(q);val t=System.nanoTime();val actual=lex.lookup(q);val elapsed=System.nanoTime()-t;check(actual==List(expected.length()){expected.getString(it)});timings.put(elapsed);check(elapsed<=100_000_000)}
  }}
  // A background cancellation during parsing, not an inference/heap-reclamation claim.
  val started=CountDownLatch(1);val done=CountDownLatch(1);val cancelled=AtomicBoolean(false);val failure=AtomicReference<Throwable?>();val checkpoints=java.util.concurrent.atomic.AtomicInteger();var cancelMs=0L
  val worker=Thread{try{store.usePack(CmuLexicon.PACK_ID){p,d->CmuLexicon.load(p,d){if(checkpoints.incrementAndGet()==128)started.countDown();if(cancelled.get())throw java.util.concurrent.CancellationException()}.close()};failure.set(AssertionError("CANCELLATION_NOT_OBSERVED"))}catch(_:java.util.concurrent.CancellationException){}catch(t:Throwable){failure.set(t)}finally{done.countDown()}}
  worker.start();check(started.await(10,TimeUnit.SECONDS));val c=SystemClock.elapsedRealtime();cancelled.set(true);check(done.await(1,TimeUnit.SECONDS));cancelMs=SystemClock.elapsedRealtime()-c;check(failure.get()==null)
  // Exceptional consumer cleanup and corruption are separately checked.
  try{store.usePack(CmuLexicon.PACK_ID){_,_->throw java.io.IOException("SYNTHETIC")}}catch(_:java.io.IOException){}
  check(store.remove(CmuLexicon.PACK_ID));var missing=false;try{store.usePack(CmuLexicon.PACK_ID){_,_->}}catch(e:IllegalStateException){missing=e.message?.startsWith("Resource unavailable")==true};check(missing)
  targetContext.assets.open("cmudict-en-us.zip").use{store.import(it)};File(root,"${CmuLexicon.PACK_ID}/data/cmudict-00.txt").outputStream().use{it.write(0)}
  try{store.usePack(CmuLexicon.PACK_ID){p,d->CmuLexicon.load(p,d).close()};error("CORRUPTION_NOT_REJECTED")}catch(_:IllegalArgumentException){}
  check(store.remove(CmuLexicon.PACK_ID));running.set(false);sampler.join();val after=javaBytes()
  report.put("loadMs",loadMs).put("queryNs",timings).put("javaBefore",baseline).put("sampledJavaPeak",peak).put("sampledJavaDelta",peak-baseline).put("nativeBefore",baselineNative).put("sampledNativePeak",peakNative).put("sampledProcessPss",peakPss).put("javaAfterCloseNoForcedGc",after).put("cancelMs",cancelMs).put("leaseProtected",true).put("removalAfterClose",true).put("missingRejected",true).put("corruptRejected",true).put("sampleIntervalMs",50)
  check(peak-baseline<=32L*1024*1024 && peakPss<=256L*1024*1024);report.put("passed",true);result.putString("status","PASS")
 }catch(t:Throwable){report.put("passed",false).put("failureClass",t.javaClass.simpleName);result.putString("status","FAIL")}
 finally{running.set(false);sampler?.join(1000);val cleaned=root.deleteRecursively();report.put("ownedRootRemoved",cleaned);if(!cleaned)result.putString("status","FAIL");result.putString("checks",report.toString());finish(Activity.RESULT_OK,result)}
 }
}
