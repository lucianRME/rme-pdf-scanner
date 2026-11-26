package org.synapseworks.pageharbor.validation

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.synapseworks.pageharbor.workflow.*

/** Synthetic mixed-profile queue; never opens a provider or an Office engine. */
class RecipeAvailabilityChecks : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() {
        val result=Bundle()
        var root:File?=null
        var store:WorkflowStore?=null
        try {
            check(targetContext.packageName.endsWith(".m36packsmoke") || targetContext.packageName.endsWith(".m36packr8"))
            check(!WorkflowPreset.OFFICE.available)
            val dir=File(targetContext.noBackupFilesDir,"availability-${UUID.randomUUID()}");root=dir;check(dir.mkdir())
            val normal=UUID.randomUUID().toString();val office=UUID.randomUUID().toString();val job=UUID.randomUUID().toString()
            fun recipe(id:String,preset:String)=JSONObject().put("id",id).put("version",1).put("label",preset)
                .put("preset",preset).put("template","{custom}-{counter}").put("custom","Document")
                .put("tree",JSONObject.NULL).put("treeIdentity",JSONObject.NULL).put("enabled",true).put("nextCounter",1)
            val bytes="synthetic frozen output".toByteArray()
            val pending=JSONObject().put("id",job).put("recipeId",office).put("recipeVersion",1)
                .put("name","Document-000001.pdf").put("preset","OFFICE").put("tree",JSONObject.NULL)
                .put("treeIdentity",JSONObject.NULL).put("revision",1).put("owner","synthetic")
                .put("duplicateKey","synthetic").put("capturedAt","1970-01-01T00:00:00Z").put("zone","UTC")
                .put("counter",1).put("digest",workflowDigest(bytes.inputStream()).first).put("bytes",bytes.size)
                .put("status","QUEUED").put("destination",JSONObject.NULL)
                .put("sourceGuards",JSONArray().put(JSONObject().put("uri","content://synthetic.invalid/input").put("device",1).put("inode",2)))
            File(dir,"$job.pdf").writeBytes(bytes)
            val metadata=JSONObject().put("version",5).put("recipes",JSONArray().put(recipe(normal,"NORMAL")).put(recipe(office,"OFFICE")))
                .put("jobs",JSONArray().put(pending)).put("grants",JSONArray())
            File(dir,"queue.json").writeText(metadata.toString())
            val opened=WorkflowStore(dir);store=opened
            check(opened.healthy && opened.data.recipes.size==2 && opened.data.jobs.size==1)
            check(opened.data.recipes.none{it.enabled} && opened.data.jobs.single().status==WorkflowStatus.PAUSED)
            check(opened.pdf(job).readBytes().contentEquals(bytes))
            val queue=WorkflowQueue(targetContext,opened);queue.active=true
            queue.saveRecipe(opened.data.recipes.single{it.id==normal}.copy(label="Supported recipe"))
            check(opened.healthy && opened.data.recipes.single{it.id==normal}.version==2)
            check(runCatching{queue.enable(office,true)}.isFailure)
            check(queue.manualRequest(job)==null)
            // Even an internal stale enabled state cannot bypass execution admission.
            opened.commit(opened.data.copy(recipes=opened.data.recipes.map{if(it.id==office)it.copy(enabled=true)else it}))
            runBlocking { queue.run(job) }
            check(opened.data.jobs.single().status==WorkflowStatus.PAUSED && opened.pdf(job).readBytes().contentEquals(bytes))
            opened.close();store=null
            val reopened=WorkflowStore(dir);store=reopened
            check(reopened.healthy && reopened.data.recipes.size==2 && reopened.data.jobs.size==1)
            check(reopened.data.recipes.none{it.enabled} && reopened.pdf(job).readBytes().contentEquals(bytes))
            result.putString("status","PASS")
            result.putBoolean("mixedQueuePreserved",true);result.putBoolean("supportedRecipeEditable",true)
            result.putBoolean("unavailableEnablePickerAndRunBlocked",true);result.putBoolean("reopenPreserved",true)
        } catch(t:Throwable) { result.putString("status","FAIL");result.putString("failureClass",t.javaClass.simpleName) }
        finally { store?.close();root?.deleteRecursively() }
        finish(Activity.RESULT_OK,result)
    }
}
