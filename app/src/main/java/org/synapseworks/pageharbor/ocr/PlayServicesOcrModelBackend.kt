package org.synapseworks.pageharbor.ocr

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOAD_PAUSED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_PENDING
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Google Play services adapter; model bytes are requested from Google, never from RME. */
internal class PlayServicesOcrModelBackend(
    context: Context,
    private val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(
        context.applicationContext,
    ),
    private val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance(),
) : OptionalOcrModelBackend {
    private val applicationContext = context.applicationContext

    override suspend fun availability(script: OcrScript): OptionalOcrModelAvailability {
        if (script == OcrScript.LATIN) return OptionalOcrModelAvailability.Installed
        if (!googlePlayServicesAvailable()) {
            return OptionalOcrModelAvailability.Failed(
                reason = OcrModelFailure.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
                retryable = true,
            )
        }

        val recognizer = try {
            createOptionalRecognizer(script)
        } catch (_: Exception) {
            return OptionalOcrModelAvailability.Failed(
                reason = OcrModelFailure.AVAILABILITY_CHECK_FAILED,
                retryable = true,
            )
        }
        return try {
            val response = moduleInstallClient.areModulesAvailable(recognizer).awaitResult()
            if (response.areModulesAvailable()) {
                OptionalOcrModelAvailability.Installed
            } else if (
                response.availabilityStatus ==
                ModuleAvailabilityResponse.AvailabilityStatus.STATUS_UNKNOWN_MODULE
            ) {
                OptionalOcrModelAvailability.Unsupported
            } else {
                OptionalOcrModelAvailability.NotInstalled
            }
        } catch (error: Exception) {
            error.toAvailabilityFailure()
        } finally {
            recognizer.close()
        }
    }

    override suspend fun install(
        script: OcrScript,
        onState: (OcrModelState) -> Unit,
    ): OptionalOcrModelInstallResult {
        if (script == OcrScript.LATIN) return OptionalOcrModelInstallResult.Installed
        if (!googlePlayServicesAvailable()) {
            return OptionalOcrModelInstallResult.Failed(
                reason = OcrModelFailure.GOOGLE_PLAY_SERVICES_UNAVAILABLE,
                retryable = true,
            )
        }

        val recognizer = try {
            createOptionalRecognizer(script)
        } catch (_: Exception) {
            return OptionalOcrModelInstallResult.Failed(
                reason = OcrModelFailure.INSTALLATION_FAILED,
                retryable = true,
            )
        }
        return requestInstall(recognizer, onState)
    }

    private fun googlePlayServicesAvailable(): Boolean =
        googleApiAvailability.isGooglePlayServicesAvailable(applicationContext) ==
            ConnectionResult.SUCCESS

    private fun createOptionalRecognizer(script: OcrScript): TextRecognizer {
        require(script != OcrScript.LATIN)
        return MlKitTextRecognizerClients.create(script)
    }

    private suspend fun requestInstall(
        recognizer: TextRecognizer,
        onState: (OcrModelState) -> Unit,
    ): OptionalOcrModelInstallResult = suspendCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        lateinit var listener: InstallStatusListener

        fun finish(result: OptionalOcrModelInstallResult) {
            if (!completed.compareAndSet(false, true)) return
            moduleInstallClient.unregisterListener(listener)
            recognizer.close()
            continuation.resume(result)
        }

        listener = InstallStatusListener { update ->
            when (update.installState) {
                STATE_PENDING -> onState(OcrModelState.Pending)
                STATE_DOWNLOADING -> onState(update.toDownloadState())
                STATE_DOWNLOAD_PAUSED -> onState(OcrModelState.Paused)
                STATE_INSTALLING -> onState(OcrModelState.Installing)
                STATE_COMPLETED -> finish(OptionalOcrModelInstallResult.Installed)
                STATE_CANCELED -> finish(OptionalOcrModelInstallResult.Canceled)
                STATE_FAILED -> finish(update.errorCode.toInstallFailure())
            }
        }

        val request = try {
            ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .setListener(listener)
                .build()
        } catch (_: Exception) {
            finish(
                OptionalOcrModelInstallResult.Failed(
                    reason = OcrModelFailure.INSTALLATION_FAILED,
                    retryable = true,
                ),
            )
            return@suspendCoroutine
        }

        try {
            moduleInstallClient.installModules(request)
                .addOnSuccessListener { response ->
                    if (response.areModulesAlreadyInstalled()) {
                        finish(OptionalOcrModelInstallResult.Installed)
                    } else {
                        onState(OcrModelState.Pending)
                    }
                }
                .addOnFailureListener { error ->
                    finish(error.toInstallFailure())
                }
        } catch (error: Exception) {
            finish(error.toInstallFailure())
        }
    }

    private fun ModuleInstallStatusUpdate.toDownloadState(): OcrModelState.Downloading {
        val progress = progressInfo ?: return OcrModelState.Downloading()
        return OcrModelState.Downloading(
            downloadedBytes = progress.bytesDownloaded,
            totalBytes = progress.totalBytesToDownload,
        )
    }

    private fun Throwable.toAvailabilityFailure(): OptionalOcrModelAvailability =
        if (this is ApiException && statusCode.isUnsupportedModuleCode()) {
            OptionalOcrModelAvailability.Unsupported
        } else {
            OptionalOcrModelAvailability.Failed(
                reason = OcrModelFailure.AVAILABILITY_CHECK_FAILED,
                retryable = true,
            )
        }

    private fun Throwable.toInstallFailure(): OptionalOcrModelInstallResult =
        (this as? ApiException)?.statusCode.toInstallFailure()

    private fun Int?.toInstallFailure(): OptionalOcrModelInstallResult = if (
        this != null && isUnsupportedModuleCode()
    ) {
        OptionalOcrModelInstallResult.Unsupported
    } else {
        OptionalOcrModelInstallResult.Failed(
            reason = OcrModelFailure.INSTALLATION_FAILED,
            retryable = true,
        )
    }

    private fun Int.isUnsupportedModuleCode(): Boolean =
        this == ModuleInstallStatusCodes.UNKNOWN_MODULE ||
            this == ModuleInstallStatusCodes.MODULE_NOT_FOUND ||
            this == ModuleInstallStatusCodes.NOT_ALLOWED_MODULE ||
            this == ModuleInstallStatusCodes.DEVELOPER_ERROR

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCoroutine { continuation ->
        addOnCompleteListener { completedTask ->
            if (completedTask.isSuccessful) {
                continuation.resume(completedTask.result)
            } else {
                continuation.resumeWithException(
                    completedTask.exception ?: IllegalStateException("Google task failed"),
                )
            }
        }
    }
}
