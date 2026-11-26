package org.synapseworks.pageharbor.office

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.synapseworks.pageharbor.workflow.WorkflowDestination
import org.synapseworks.pageharbor.workflow.WorkflowSourceGuard

/** Same-process, single-use handoff. No source URI or document draft in Activity results.
 * Only the sole queue owner's registered callback may journal/take a read grant.
 */
internal class OfficeRecipeOffer internal constructor(
    val id: String, val request: String, val file: File, val digest: String,
    val pages: Int, val owner: String, val source: WorkflowSourceGuard, val sourceDigest: String,
    internal val releaseSource: () -> Unit,
) : AutoCloseable {
    override fun toString() = "OfficeRecipeOffer(<redacted>)"
    override fun close() = OfficeRecipeOffers.release(this)
}

internal object OfficeRecipeOffers {
    const val REQUEST = "office_recipe_request"
    const val RESULT = "office_recipe_offer"
    private val offers = mutableMapOf<String, OfficeRecipeOffer>()
    private val claimed = mutableSetOf<String>()
    private val requests = mutableMapOf<String, (WorkflowSourceGuard) -> (() -> Unit)>()
    private val uuid = Regex("[a-f0-9-]{36}")
    private fun root(context: Context) = File(context.noBackupFilesDir, "office-offers")
    @Synchronized fun register(request:String,reserve:(WorkflowSourceGuard)->(() -> Unit)) {
        require(request.matches(uuid) && requests.isEmpty());requests[request]=reserve
    }
    @Synchronized fun endRequest(request:String?) { requests.remove(request) }

    @Synchronized fun recover(context: Context) {
        val root = root(context)
        require(root.mkdirs() || root.isDirectory)
        val files = root.listFiles().orEmpty()
        require(files.size <= 2)
        files.forEach { file ->
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile &&
                file.name.matches(Regex("[a-f0-9-]{36}\\.(pdf|tmp)")))
            if (offers.values.none { it.file == file }) check(file.delete())
        }
    }

    @Synchronized fun create(context: Context, request: String, source: OfficeSource,
        output: OfficeOutput, check: () -> Unit): OfficeRecipeOffer {
        require(request.matches(uuid) && offers.isEmpty())
        recover(context)
        require(root(context).usableSpace >= 320L * 1024 * 1024)
        require(WorkflowDestination(context).localSource(source.uri, source.descriptor))
        source.unchanged(check); output.verify(check)
        val guard=WorkflowSourceGuard(source.uri.toString(),source.identity.device,source.identity.inode)
        // Preserve the exact read permission while the OpenDocument activity still owns it.
        // The callback writes the queue journal BEFORE taking permission; no second store.
        val release=checkNotNull(requests[request])(guard)
        val id = UUID.randomUUID().toString()
        val tmp = File(root(context), "$id.tmp"); val pdf = File(root(context), "$id.pdf")
        try {
            output.file.inputStream().use { input -> FileOutputStream(tmp).use { out ->
                val b = ByteArray(65536); var bytes = 0L
                while (true) { check(); val n = input.read(b); if (n < 0) break
                    bytes += n; require(bytes <= OFFICE_OUTPUT); out.write(b, 0, n) }
                out.fd.sync()
            } }
            require(officeDigest(tmp, check) == output.digest)
            source.unchanged(check); output.verify(check); check()
            require(tmp.setReadOnly() && tmp.renameTo(pdf))
            return OfficeRecipeOffer(id, request, pdf, output.digest, output.pages, output.owner,
                guard, source.digest, release
            ).also { offers[id] = it }
        } catch (failure: Throwable) { tmp.delete(); pdf.delete(); release(); throw failure }
    }

    @Synchronized fun claim(id: String, request: String): OfficeRecipeOffer? {
        val offer = offers[id]?.takeIf { it.request == request && id !in claimed } ?: return null
        claimed += id; return offer
    }
    @Synchronized fun discard(id: String?) {
        // Failed file/grant cleanup stays quarantined or journaled. A cancelled
        // Activity result must not crash the caller or log provider exceptions.
        offers[id]?.takeIf { it.id !in claimed }?.let { runCatching { it.close() } }
    }
    @Synchronized internal fun release(offer: OfficeRecipeOffer) {
        if (offers[offer.id] !== offer) return
        // Failed cleanup remains visible on disk and blocks the next recovery.
        check(!offer.file.exists() || offer.file.delete())
        offers.remove(offer.id); claimed.remove(offer.id)
        offer.releaseSource()
    }
}
