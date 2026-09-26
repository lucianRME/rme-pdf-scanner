package org.synapseworks.pageharbor.ocr

/** Stable application-level OCR scripts. These identifiers are independent of any SDK class. */
enum class OcrScript(val stableId: String) {
    LATIN("LATIN"),
    CHINESE("CHINESE"),
    JAPANESE("JAPANESE"),
    KOREAN("KOREAN"),
    DEVANAGARI("DEVANAGARI"),
    ;

    companion object {
        fun fromStableId(value: String?): OcrScript? =
            entries.firstOrNull { script -> script.stableId == value }
    }
}

/** User selection policy; automatic recommendation remains explicitly overridable. */
sealed interface OcrScriptSelection {
    val stableId: String

    data object Automatic : OcrScriptSelection {
        override val stableId: String = AUTOMATIC_STABLE_ID
    }

    data class Explicit(val script: OcrScript) : OcrScriptSelection {
        override val stableId: String = script.stableId
    }

    companion object {
        const val AUTOMATIC_STABLE_ID = "AUTOMATIC"

        fun fromStableId(value: String?): OcrScriptSelection? = when (value) {
            AUTOMATIC_STABLE_ID -> Automatic
            else -> OcrScript.fromStableId(value)?.let(::Explicit)
        }

        /** Null and invalid stored preferences recover safely to automatic recommendation. */
        fun fromStoredValue(value: String?): OcrScriptSelection =
            fromStableId(value) ?: Automatic
    }
}

fun OcrScriptSelection.resolve(recommendation: OcrScriptRecommendation): OcrScript = when (this) {
    OcrScriptSelection.Automatic -> recommendation.script
    is OcrScriptSelection.Explicit -> script
}

/** Engine-neutral model state independent of recognizer implementation details. */
sealed interface OcrModelState {
    data object Bundled : OcrModelState
    data object Checking : OcrModelState
    data object Installed : OcrModelState
    data object NotInstalled : OcrModelState
    data object Pending : OcrModelState

    data class Downloading(
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
    ) : OcrModelState {
        init {
            require((downloadedBytes == null) == (totalBytes == null)) {
                "Download progress requires both byte values or neither"
            }
            if (downloadedBytes != null && totalBytes != null) {
                require(totalBytes > 0L) { "totalBytes must be positive" }
                require(downloadedBytes in 0L..totalBytes) {
                    "downloadedBytes must be within the download total"
                }
            }
        }

        val fraction: Double?
            get() = if (downloadedBytes != null && totalBytes != null) {
                downloadedBytes.toDouble() / totalBytes.toDouble()
            } else {
                null
            }
    }

    data object Paused : OcrModelState
    data object Installing : OcrModelState
    data object Canceled : OcrModelState
    data object StatusUnknown : OcrModelState
    data class Failed(val reason: OcrModelFailure) : OcrModelState
}

/** Safe model-management failures; no SDK exception or document detail crosses this boundary. */
enum class OcrModelFailure {
    GOOGLE_PLAY_SERVICES_UNAVAILABLE,
    AVAILABILITY_CHECK_FAILED,
    INSTALLATION_FAILED,
}
