package org.synapseworks.pageharbor.migration

import android.net.Uri
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.importing.MAX_PDF_IMPORT_BYTES
import org.synapseworks.pageharbor.document.importing.sniffSupportedContentType
import org.synapseworks.pageharbor.document.session.DEFAULT_MAX_IMAGE_SOURCE_BYTES

internal class AndroidMigrationSourceRevalidator(
    private val sourceAdapter: AndroidMigrationSourceAdapter,
) : MigrationSourceRevalidator {
    override suspend fun revalidate(
        source: InspectedMigrationSource,
        cancellationSignal: MigrationCancellationSignal,
    ): MigrationSourceRevalidation = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (cancellationSignal.isCancellationRequested()) throw CancellationException()
        val refreshed = sourceAdapter.describe(
            uri = Uri.parse(source.source.id),
            relativeFolderPath = source.source.relativeFolderPath,
            declaredContentType = source.source.declaredContentType,
            platformAccessFlags = source.source.platformAccessFlags,
            origin = source.source.origin,
            discoveryOrder = source.source.discoveryOrder,
            discoveryOrderEvidence = source.source.discoveryOrderEvidence,
        )
        val evidence = linkedSetOf<MigrationSourceRevalidationEvidence>()
        val detected = try {
            sourceAdapter.open(source.source).use(::sniffSupportedContentType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return@withContext source.result(MigrationSourceRevalidationStatus.UNAVAILABLE)
        } catch (_: SecurityException) {
            return@withContext source.result(MigrationSourceRevalidationStatus.UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            return@withContext source.result(MigrationSourceRevalidationStatus.UNAVAILABLE)
        }
        evidence += MigrationSourceRevalidationEvidence.STREAM_SIGNATURE
        if (detected != source.contentType.mimeType) {
            return@withContext source.result(
                MigrationSourceRevalidationStatus.FAILED_VALIDATION,
                evidence,
                refreshed,
            )
        }
        source.source.sizeBytes?.let { plannedSize ->
            refreshed.sizeBytes?.let { currentSize ->
                evidence += MigrationSourceRevalidationEvidence.PROVIDER_SIZE
                if (plannedSize != currentSize) {
                    return@withContext source.result(
                        MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS,
                        evidence,
                        refreshed,
                    )
                }
            }
        }
        source.source.modifiedAtMillis?.let { plannedModified ->
            refreshed.modifiedAtMillis?.let { currentModified ->
                evidence += MigrationSourceRevalidationEvidence.PROVIDER_MODIFIED_TIME
                if (plannedModified != currentModified) {
                    return@withContext source.result(
                        MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS,
                        evidence,
                        refreshed,
                    )
                }
            }
        }
        var currentPageCount: Int? = null
        if (source.contentType == MigrationContentType.PDF) {
            when (val pdf = sourceAdapter.inspectPdf(source.source)) {
                is MigrationPdfInspection.Readable -> {
                    currentPageCount = pdf.pageCount
                    evidence += MigrationSourceRevalidationEvidence.PDF_PAGE_COUNT
                    if (source.pageCount != null && source.pageCount != pdf.pageCount) {
                        return@withContext source.result(
                            MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS,
                            evidence,
                            refreshed,
                            currentPageCount,
                        )
                    }
                }
                MigrationPdfInspection.Unavailable -> if (source.pageCount != null) {
                    return@withContext source.result(
                        MigrationSourceRevalidationStatus.FAILED_VALIDATION,
                        evidence,
                        refreshed,
                    )
                }
                MigrationPdfInspection.Malformed,
                MigrationPdfInspection.PageLimitExceeded,
                -> return@withContext source.result(
                    MigrationSourceRevalidationStatus.FAILED_VALIDATION,
                    evidence,
                    refreshed,
                )
            }
        }
        source.contentSha256?.let { plannedSha256 ->
            val currentSha256 = sourceAdapter.sha256OrNull(
                source = source.source,
                maximumBytes = if (source.contentType == MigrationContentType.PDF) {
                    MAX_PDF_IMPORT_BYTES
                } else {
                    DEFAULT_MAX_IMAGE_SOURCE_BYTES
                },
                cancellationSignal = cancellationSignal,
            ) ?: return@withContext source.result(
                MigrationSourceRevalidationStatus.FAILED_VALIDATION,
                evidence,
                refreshed,
                currentPageCount,
            )
            evidence += MigrationSourceRevalidationEvidence.PREVIOUS_SHA256
            if (currentSha256 != plannedSha256) {
                return@withContext source.result(
                    MigrationSourceRevalidationStatus.CHANGED_REQUIRES_REANALYSIS,
                    evidence,
                    refreshed,
                    currentPageCount,
                )
            }
        }
        source.result(
            MigrationSourceRevalidationStatus.UNCHANGED,
            evidence,
            refreshed,
            currentPageCount,
        )
    }
}

internal suspend fun MigrationSourceAccess.sha256OrNull(
    source: MigrationSource,
    maximumBytes: Long,
    cancellationSignal: MigrationCancellationSignal,
): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    var byteCount = 0L
    open(source).use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (cancellationSignal.isCancellationRequested()) throw CancellationException()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            byteCount += count
            if (byteCount > maximumBytes) return null
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun InspectedMigrationSource.result(
    status: MigrationSourceRevalidationStatus,
    evidence: Set<MigrationSourceRevalidationEvidence> = emptySet(),
    refreshed: MigrationSource? = null,
    currentPageCount: Int? = null,
) = MigrationSourceRevalidation(
    sourceDiscoveryId = source.discoveryIdentity,
    status = status,
    evidence = evidence,
    currentSizeBytes = refreshed?.sizeBytes,
    currentModifiedAtMillis = refreshed?.modifiedAtMillis,
    currentPageCount = currentPageCount,
)

private const val HASH_BUFFER_SIZE = 32 * 1024
