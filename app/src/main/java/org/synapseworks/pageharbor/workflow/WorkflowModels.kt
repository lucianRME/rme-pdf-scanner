package org.synapseworks.pageharbor.workflow

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal enum class WorkflowPreset(val label: String, val available: Boolean = true) {
    NORMAL("Rendered PDF"), SEARCHABLE("PDF with current positioned OCR"),
    SMALL("PDF up to 1 MiB"), LARGE("PDF up to 5 MiB"), ENCRYPTED("Password-encrypted PDF"),
    ARCHIVAL("PDF/A-1b · image-only archival"), DOCX("Editable DOCX · reviewed reflow Letter"), XLSX("XLSX · reviewed tables and expense reports"), PPTX("PPTX · reviewed image or native slides"), OFFICE("Office to PDF · reviewed local source", org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED);
    val extension get()=when(this){DOCX->"docx";XLSX->"xlsx";PPTX->"pptx";else->"pdf"}
    val mime get()=when(this){DOCX->org.synapseworks.pageharbor.docx.DOCX_MIME;XLSX->org.synapseworks.pageharbor.xlsx.XLSX_MIME;PPTX->org.synapseworks.pageharbor.pptx.PPTX_MIME;else->"application/pdf"}
    val excludesSources get()=this in setOf(ARCHIVAL,DOCX,XLSX,PPTX,OFFICE)
}
internal enum class WorkflowStatus { QUEUED, PAUSED, PICKER, WRITING, MANUAL_REQUIRED, COLLISION, FAILED, UNCERTAIN, SAVED, CANCELLED }
internal data class WorkflowRecipe(
    val id: String, val version: Int = 1, val label: String, val preset: WorkflowPreset,
    val template: String = "{custom}-{date}-{counter}", val custom: String = "Document",
    val tree: String? = null, val enabled: Boolean = false, val nextCounter: Int = 1, val treeIdentity: String? = null,
)
internal data class WorkflowJob(
    val id: String, val recipeId: String, val recipeVersion: Int, val name: String,
    val preset: WorkflowPreset, val tree: String?, val revision: Long, val owner: String,
    val duplicateKey: String, val capturedAt: String, val zone: String, val counter: Int,
    val digest: String, val bytes: Long, val status: WorkflowStatus = WorkflowStatus.PAUSED,
    val destination: String? = null, val treeIdentity: String? = null, val sourceGuards:List<WorkflowSourceGuard> = emptyList(),
)
internal data class WorkflowData(val recipes: List<WorkflowRecipe> = emptyList(), val jobs: List<WorkflowJob> = emptyList(), val grants: List<String> = emptyList())
internal object WorkflowNaming {
    fun name(template: String, custom: String, at: Instant, zone: ZoneId, counter: Int, extension:String="pdf"): String {
        require(extension in setOf("pdf","docx","xlsx","pptx"))
        require(counter in 1..999999 && template.length in 1..160)
        val component = Normalizer.normalize(custom, Normalizer.Form.NFC)
        checkComponent(component)
        val date = at.atZone(zone).toLocalDate().toString()
        val result = template.replace("{date}", date).replace("{counter}", counter.toString().padStart(6, '0')).replace("{custom}", component)
        checkComponent(result)
        return "$result.$extension".also { require(it.toByteArray(Charsets.UTF_8).size <= 120) }
    }
    fun verifyExported(name:String,extension:String="pdf") {
        require(extension in setOf("pdf","docx","xlsx","pptx"))
        require(name.endsWith(".$extension") && name.toByteArray(Charsets.UTF_8).size<=120 && Normalizer.normalize(name,Normalizer.Form.NFC)==name)
        checkComponent(name.removeSuffix(".$extension"))
    }
    private fun checkComponent(s: String) {
        require(s.isNotBlank() && s == s.trim() && !s.endsWith('.') && s != "." && s != "..")
        require(s.codePoints().noneMatch { Character.getType(it)==Character.SURROGATE.toInt() || it==0x2028 || it==0x2029 })
        require(s.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it in "\\/:*?\"<>|{}" })
        require(!s.contains("..") && !s.contains('.'))
        require(s.uppercase(Locale.ROOT) !in setOf("CON", "PRN", "AUX", "NUL") && !Regex("(?i)(COM|LPT)[1-9]").matches(s))
    }
}
internal const val WORKFLOW_MAX_PDF = 32L * 1024 * 1024
internal const val WORKFLOW_MAX_OWNED = 256L * 1024 * 1024
internal fun WorkflowStatus.pending() = this !in setOf(WorkflowStatus.SAVED, WorkflowStatus.CANCELLED)
