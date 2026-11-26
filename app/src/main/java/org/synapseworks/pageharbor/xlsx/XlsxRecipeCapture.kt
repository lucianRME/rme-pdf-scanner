package org.synapseworks.pageharbor.xlsx

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.workflow.*

/** The queue preview describes the frozen workbook; it is deliberately not an Office renderer. */
internal fun captureXlsxRecipe(root:File,prepared:XlsxPrepared,check:()->Unit):WorkflowCaptureResult {
    check();require(prepared.book.problem()==null&&prepared.digest.second in 1..XLSX_MAX)
    val directory=File(root,"capture-${UUID.randomUUID()}");require(directory.mkdir());var retained=false
    try {
        require(root.usableSpace>=32L*1024*1024)
        require(workflowDigest(prepared.file.inputStream(),XLSX_MAX,check)==prepared.digest)
        val output=File(directory,"reviewed.xlsx")
        prepared.file.inputStream().use{input->output.outputStream().use{out->val b=ByteArray(65536);while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}}
        check();require(workflowDigest(output.inputStream(),XLSX_MAX,check)==prepared.digest)
        val preview=File(directory,"summary.pdf")
        val pdf=PdfDocument()
        try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.BLACK;textSize=18f}
            page.canvas.drawText("Reviewed workbook",36f,60f,paint);paint.textSize=12f
            val sheets=prepared.book.tables.size+1+if(prepared.book.expenses.isEmpty())0 else 2
            listOf("$sheets worksheets; ${prepared.book.tables.size} tables; ${prepared.book.expenses.size} receipts.","Values, types, sources and totals were reviewed before capture.","This summary is not a spreadsheet rendering.","Queued XLSX bytes and settings are frozen.").forEachIndexed{i,text->check();page.canvas.drawText(text,36f,100f+i*24,paint)}
            pdf.finishPage(page);preview.outputStream().use{pdf.writeTo(it)}
        }finally{pdf.close()}
        require(preview.length() in 1..65536);check();retained=true
        return WorkflowCaptureResult(directory,output,preview,prepared.digest.first,prepared.digest.second,workflowDigest(preview.inputStream(),65536,check).first,prepared.guards.toList())
    }finally{if(!retained)directory.deleteRecursively()}
}
