package org.synapseworks.pageharbor.validation
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.*
import org.synapseworks.pageharbor.translation.*
import org.synapseworks.pageharbor.resourcepack.*
/** Public frozen corpus only; never accepts documents, text arguments or user queries. */
class TranslationCorpusChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val result=Bundle();try{
  check(targetContext.packageName.endsWith(".m36packsmoke")||targetContext.packageName.endsWith(".m36packr8"))
  val corpus=JSONObject(targetContext.assets.open("m37-corpus.json").bufferedReader().use{it.readText()}).getJSONArray("cases");val outputs=JSONArray();val store=ModelPackStore(File(targetContext.noBackupFilesDir,"binary-model-packs-v1"))
  store.usePack{dir->val type=Class.forName("org.synapseworks.pageharbor.resourcepack.KnownModelRuntime");val client=type.getConstructor(File::class.java).newInstance(dir) as AutoCloseable
   try{check(!ModelPackStore(File(targetContext.noBackupFilesDir,"binary-model-packs-v1")).remove());type.getMethod("check",AtomicBoolean::class.java).invoke(client,AtomicBoolean(false))
    for(i in 0 until corpus.length()){val source=corpus.getJSONObject(i).getString("source");val plan=TranslationPlan.plan(listOf(TranslationPage("synthetic-$i",source)));val translated=StringBuilder();for(p in plan){if(p.separator)translated.append(p.source) else{check(type.getMethod("fits",String::class.java).invoke(client,p.source)==true);translated.append(type.getMethod("translate",String::class.java,AtomicBoolean::class.java).invoke(client,p.source,AtomicBoolean(false)) as String)}};outputs.put(JSONObject().put("source",source).put("automatic",translated.toString()).put("reference",corpus.getJSONObject(i).getString("reference")))}
    val cooperative=AtomicBoolean(false);val requester=Thread{Thread.sleep(5);cooperative.set(true)};requester.start();try{type.getMethod("translate",String::class.java,AtomicBoolean::class.java).invoke(client,"The document is on the table.",cooperative);error("COOPERATIVE_CANCEL_IGNORED")}catch(t:java.lang.reflect.InvocationTargetException){check(t.cause is java.util.concurrent.CancellationException)}finally{requester.join()}
    val stop=AtomicBoolean(true);try{type.getMethod("translate",String::class.java,AtomicBoolean::class.java).invoke(client,"Hello world.",stop);error("CANCEL_ACCEPTED")}catch(t:java.lang.reflect.InvocationTargetException){check(t.cause is java.util.concurrent.CancellationException)}
   }finally{client.close()}
  };result.putString("automaticSyntheticOutputs",outputs.toString());result.putString("status","PASS");result.putString("cancellationScope","Before inference and cooperative flag during loaded generation; no native interruption claim")
 }catch(t:Throwable){result.putString("status","FAIL");result.putString("failureClass",t.javaClass.simpleName)};finish(Activity.RESULT_OK,result)}
}
