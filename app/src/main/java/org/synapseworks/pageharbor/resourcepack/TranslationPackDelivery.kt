package org.synapseworks.pageharbor.resourcepack

import android.app.Activity
import java.io.File

/** Pack names and filenames are fixed application data, never document-derived URLs. */
internal enum class TranslationPack(val assetPack: String, val archive: String) {
    ROMANIAN("translation_ro", "opus-en-ro-v1.zip"),
    MULTILINGUAL("translation_multi", "rme-multilingual-v1.zip"),
    IRISH("translation_ga", "rme-irish-v1.zip"),
}

internal data class TranslationPackLanguage(val name: String, val pack: TranslationPack, val directions: String)

internal val translationPackLanguages = listOf(
    TranslationPackLanguage("Romanian", TranslationPack.ROMANIAN, "English → Romanian"),
    TranslationPackLanguage("Irish", TranslationPack.IRISH, "English ↔ Irish"),
) + listOf("French", "German", "Italian", "Spanish", "Japanese", "Korean", "Chinese", "Hindi", "Marathi", "Nepali")
    .map { TranslationPackLanguage(it, TranslationPack.MULTILINGUAL, "English ↔ $it") }

/** Implemented only in an explicitly provisioned Play delivery build. No network fallback. */
internal interface TranslationPackDelivery : AutoCloseable {
    fun downloadSize(pack: TranslationPack, result: (Long?) -> Unit)
    fun request(pack: TranslationPack, update: (String, Boolean) -> Unit, ready: (File) -> Unit, failed: () -> Unit)
    fun confirm()
    fun cancel()
    fun remove(pack: TranslationPack)

    companion object {
        fun create(activity: Activity): TranslationPackDelivery? = runCatching {
            Class.forName("org.synapseworks.pageharbor.resourcepack.PlayTranslationPackDelivery")
                .getConstructor(Activity::class.java).newInstance(activity) as TranslationPackDelivery
        }.getOrNull()
    }
}
