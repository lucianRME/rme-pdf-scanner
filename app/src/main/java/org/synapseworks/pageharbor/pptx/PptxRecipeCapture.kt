package org.synapseworks.pageharbor.pptx

import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.workflow.*

/** Adopts only independently generated, reviewed immutable bytes; never reruns reconstruction. */
internal fun capturePptxRecipe(root:File,prepared:PptxPrepared,check:()->Unit):WorkflowCaptureResult {
    check();require(prepared.deck.problem()==null&&prepared.digest.second in 1..PPTX_MAX)
    require(root.usableSpace>=64L*1024*1024)
    val dir=File(root,"capture-${UUID.randomUUID()}");require(dir.mkdir());var retained=false
    try {
        fun copy(source:File,target:File,max:Long,expected:Pair<String,Long>) {
            require(workflowDigest(source.inputStream(),max,check)==expected)
            source.inputStream().use{input->target.outputStream().use{out->val buffer=ByteArray(65536);var count=0L;while(true){check();val n=input.read(buffer);if(n<0)break;count+=n;require(count<=max);out.write(buffer,0,n)}}}
            require(workflowDigest(target.inputStream(),max,check)==expected)
        }
        val output=File(dir,"reviewed.pptx");copy(prepared.file,output,PPTX_MAX,prepared.digest)
        val preview=File(dir,"plan.pdf");copy(prepared.preview,preview,16L*1024*1024,prepared.previewDigest to prepared.preview.length())
        check();retained=true
        return WorkflowCaptureResult(dir,output,preview,prepared.digest.first,prepared.digest.second,prepared.previewDigest,prepared.guards.toList())
    }finally{if(!retained)dir.deleteRecursively()}
}
