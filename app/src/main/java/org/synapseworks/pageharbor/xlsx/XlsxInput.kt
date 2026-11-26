package org.synapseworks.pageharbor.xlsx

import android.content.Context
import android.net.Uri
import org.synapseworks.pageharbor.workflow.*

/** In-memory source exclusions only. Never emitted in workbook content, logs or saved state. */
internal data class XlsxInput(val kind:String,val guard:WorkflowSourceGuard?=null,val digest:Pair<String,Long>?=null) {
    val exportable get()=kind in setOf("Manual entry","Camera capture")||kind=="Verified image"&&guard!=null&&digest!=null
    override fun toString()="XlsxInput(<redacted>)"
    companion object {
        fun image(context:Context,uri:Uri,check:()->Unit):XlsxInput {
            check()
            return try {
                val before=workflowSourceGuard(context,uri)
                val hash=workflowDigest(requireNotNull(context.contentResolver.openInputStream(uri)),16L*1024*1024,check)
                val after=workflowSourceGuard(context,uri)
                require(before==after);check();XlsxInput("Verified image",before,hash)
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){XlsxInput("Unverified image")}
        }
    }
}
