package org.synapseworks.pageharbor.ocr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Production Phase 3A runtime. It has no user-facing UI and stores no SDK types in Room. */
class MultilingualOcrRuntime private constructor(context: Context) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelBackend = PlayServicesOcrModelBackend(context.applicationContext)

    val modelInstaller: OcrModelInstaller = CoalescingOcrModelInstaller(
        backend = modelBackend,
        processScope = processScope,
    )
    val recognitionEngine: OcrPageRecognitionEngine = OcrRecognitionRouter(
        modelStatusProvider = modelInstaller,
        recognizerFactory = MlKitOcrRecognizerFactory(),
    )

    companion object {
        @Volatile
        private var instance: MultilingualOcrRuntime? = null

        /** Application-context singleton survives Activity recreation and coalesces install work. */
        fun get(context: Context): MultilingualOcrRuntime = instance ?: synchronized(this) {
            instance ?: MultilingualOcrRuntime(context.applicationContext).also { created ->
                instance = created
            }
        }
    }
}
