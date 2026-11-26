package org.synapseworks.pageharbor.resourcepack

import android.app.Activity
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

/** Play owns transport; existing ModelPackStore owns pin checking, staging and publication. */
internal class PlayTranslationPackDelivery(activity: Activity) : TranslationPackDelivery {
    private val activity = activity
    private val manager = AssetPackManagerFactory.getInstance(activity)
    private var selected: TranslationPack? = null
    private var update: ((String, Boolean) -> Unit)? = null
    private var ready: ((File) -> Unit)? = null
    private var failed: (() -> Unit)? = null
    private var closed = false
    private var generation = 0L
    private val listener = AssetPackStateUpdateListener(::state)

    init { manager.registerListener(listener) }

    override fun downloadSize(pack: TranslationPack, result: (Long?) -> Unit) {
        manager.getPackStates(listOf(pack.assetPack))
            .addOnSuccessListener { if (!closed) result(it.packStates()[pack.assetPack]?.totalBytesToDownload()) }
            .addOnFailureListener { if (!closed) result(null) }
    }

    override fun request(pack: TranslationPack, update: (String, Boolean) -> Unit, ready: (File) -> Unit, failed: () -> Unit) {
        check(!closed && selected == null)
        selected = pack; this.update = update; this.ready = ready; this.failed = failed
        val requestGeneration = generation
        // Re-query every request. Play may remove or move delivered assets between launches.
        if (complete(pack)) return
        manager.fetch(listOf(pack.assetPack))
            .addOnSuccessListener { if (generation == requestGeneration) it.packStates()[pack.assetPack]?.let(::state) }
            .addOnFailureListener { if (generation == requestGeneration) fail() }
    }

    private fun state(state: AssetPackState) {
        val pack = selected ?: return
        if (closed || state.name() != pack.assetPack) return
        when (state.status()) {
            AssetPackStatus.PENDING -> update?.invoke("Waiting for language download…", false)
            AssetPackStatus.DOWNLOADING -> {
                val total = state.totalBytesToDownload()
                val percent = if (total > 0) (100 * state.bytesDownloaded() / total).coerceIn(0, 100) else 0
                update?.invoke("Downloading language · $percent%", false)
            }
            AssetPackStatus.TRANSFERRING -> update?.invoke("Preparing downloaded language…", false)
            AssetPackStatus.COMPLETED -> if (!complete(pack)) fail()
            AssetPackStatus.WAITING_FOR_WIFI -> update?.invoke("Waiting for Wi-Fi. Continue to review mobile data use.", true)
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> update?.invoke("Google Play needs confirmation. Continuing may update this app.", true)
            AssetPackStatus.FAILED, AssetPackStatus.CANCELED -> fail()
        }
    }

    private fun complete(pack: TranslationPack): Boolean {
        val root = manager.getPackLocation(pack.assetPack)?.assetsPath() ?: return false
        val archive = File(root, pack.archive)
        if (!archive.isFile) return false
        val callback = ready
        clearRequest()
        callback?.invoke(archive)
        return true
    }

    private fun fail() {
        if (selected == null || closed) return
        val callback = failed; clearRequest(); callback?.invoke()
    }

    override fun confirm() {
        val requestGeneration = generation
        if (selected != null && !closed) manager.showConfirmationDialog(activity)
            .addOnFailureListener { if (generation == requestGeneration) fail() }
    }

    override fun cancel() {
        val pack = selected; clearRequest()
        if (pack != null) runCatching { manager.cancel(listOf(pack.assetPack)) }
    }

    override fun remove(pack: TranslationPack) { if (!closed) runCatching { manager.removePack(pack.assetPack) } }
    private fun clearRequest() { generation++; selected = null; update = null; ready = null; failed = null }
    override fun close() { cancel(); closed = true; manager.unregisterListener(listener) }
}
