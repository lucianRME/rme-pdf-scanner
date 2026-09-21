package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.backup.crypto.BackupEnvelopeException
import org.synapseworks.pageharbor.backup.crypto.EncryptedBackupEnvelope
import org.synapseworks.pageharbor.backup.crypto.EnvelopeFailure

enum class RestoreInputKind {
    ZIP,
    ENCRYPTED,
}

sealed interface RestoreSourceInspection {
    data class Supported(
        val kind: RestoreInputKind,
        val requiresPassword: Boolean,
    ) : RestoreSourceInspection

    data object Unsupported : RestoreSourceInspection
    data object Unavailable : RestoreSourceInspection
}

enum class RestoreCoordinatorFailure {
    SOURCE_UNAVAILABLE,
    UNSUPPORTED_INPUT,
    WRONG_PASSWORD_OR_DAMAGED_ENCRYPTED_BACKUP,
    INVALID_ENCRYPTED_BACKUP,
    CRYPTO_UNAVAILABLE,
    TEMPORARY_STORAGE_UNAVAILABLE,
    TEMPORARY_CLEANUP_FAILED,
    INVALID_OR_CORRUPT_BACKUP,
    INSUFFICIENT_STORAGE,
    LIBRARY_UNAVAILABLE,
}

sealed interface RestoreCoordinatorPrepareResult {
    data class Ready(val prepared: AndroidPreparedRestore) : RestoreCoordinatorPrepareResult
    data object PasswordRequired : RestoreCoordinatorPrepareResult

    data class Failed(
        val reason: RestoreCoordinatorFailure,
        val detailCode: String? = null,
    ) : RestoreCoordinatorPrepareResult
}

class AndroidPreparedRestore internal constructor(
    internal val core: PreparedRestore,
) : AutoCloseable {
    val preview: RestorePreview
        get() = core.preview

    val isConsumed: Boolean
        get() = core.isConsumed

    override fun close() {
        core.close()
    }
}

/**
 * Android SAF boundary for restore. URI and password values are operation-local and are never
 * persisted. Both ordinary and decrypted ZIP bytes are copied to a private no-backup staging file,
 * which is deleted immediately after the verified core preview has consumed it.
 */
class AndroidRestoreCoordinator private constructor(
    private val engine: LibraryRestoreEngine,
    private val safAccess: RestoreSafAccess,
    private val archiveWorkspace: RestoreArchiveWorkspace,
) {
    constructor(context: Context) : this(
        engine = LibraryRestoreEngine(
            store = RoomRestoreLibraryStore(context.applicationContext),
            stagingWorkspace = AndroidRestoreStagingWorkspace(context.applicationContext),
        ),
        safAccess = AndroidRestoreSafAccess(context.applicationContext),
        archiveWorkspace = AndroidRestoreArchiveWorkspace(context.applicationContext),
    )

    internal constructor(
        engine: LibraryRestoreEngine,
        safAccess: RestoreSafAccess,
        archiveWorkspace: RestoreArchiveWorkspace,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit,
    ) : this(engine, safAccess, archiveWorkspace)

    suspend fun inspect(uri: Uri): RestoreSourceInspection = inspect(RestoreSafReference(uri.toString()))

    internal suspend fun inspect(reference: RestoreSafReference): RestoreSourceInspection =
        withContext(Dispatchers.IO) {
            try {
                when (sniff(reference)) {
                    RestoreInputKind.ZIP -> RestoreSourceInspection.Supported(
                        RestoreInputKind.ZIP,
                        requiresPassword = false,
                    )
                    RestoreInputKind.ENCRYPTED -> RestoreSourceInspection.Supported(
                        RestoreInputKind.ENCRYPTED,
                        requiresPassword = true,
                    )
                    null -> RestoreSourceInspection.Unsupported
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RestoreSourceInspection.Unavailable
            }
        }

    suspend fun prepare(
        uri: Uri,
        password: CharArray? = null,
    ): RestoreCoordinatorPrepareResult = prepare(RestoreSafReference(uri.toString()), password)

    internal suspend fun prepare(
        reference: RestoreSafReference,
        password: CharArray? = null,
    ): RestoreCoordinatorPrepareResult = withContext(Dispatchers.IO) {
        val kind = try {
            sniff(reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext RestoreCoordinatorPrepareResult.Failed(
                RestoreCoordinatorFailure.SOURCE_UNAVAILABLE,
            )
        } ?: return@withContext RestoreCoordinatorPrepareResult.Failed(
            RestoreCoordinatorFailure.UNSUPPORTED_INPUT,
        )

        if (kind == RestoreInputKind.ENCRYPTED && (password == null || password.isEmpty())) {
            return@withContext RestoreCoordinatorPrepareResult.PasswordRequired
        }
        val archiveFile = try {
            archiveWorkspace.create()
        } catch (_: Exception) {
            return@withContext RestoreCoordinatorPrepareResult.Failed(
                RestoreCoordinatorFailure.TEMPORARY_STORAGE_UNAVAILABLE,
            )
        }

        var coreResult: RestorePreparationResult? = null
        var earlyResult: RestoreCoordinatorPrepareResult? = null
        try {
            when (kind) {
                RestoreInputKind.ZIP -> copyPlainArchive(reference, archiveFile)
                RestoreInputKind.ENCRYPTED -> decryptArchive(
                    reference = reference,
                    destination = archiveFile,
                    password = requireNotNull(password),
                )
            }
            currentCoroutineContext().ensureActive()
            coreResult = engine.prepare(
                RestoreArchiveSource(archiveFile.length()) { FileInputStream(archiveFile) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackupEnvelopeException) {
            earlyResult = RestoreCoordinatorPrepareResult.Failed(failure.toCoordinatorFailure())
        } catch (_: FileNotFoundException) {
            earlyResult = RestoreCoordinatorPrepareResult.Failed(
                RestoreCoordinatorFailure.SOURCE_UNAVAILABLE,
            )
        } catch (_: IOException) {
            earlyResult = RestoreCoordinatorPrepareResult.Failed(
                RestoreCoordinatorFailure.TEMPORARY_STORAGE_UNAVAILABLE,
            )
        } catch (_: Exception) {
            earlyResult = RestoreCoordinatorPrepareResult.Failed(
                RestoreCoordinatorFailure.INVALID_OR_CORRUPT_BACKUP,
            )
        } finally {
            val cleaned = try {
                archiveWorkspace.delete(archiveFile)
            } catch (_: Exception) {
                false
            }
            if (!cleaned) {
                (coreResult as? RestorePreparationResult.Ready)?.prepared?.close()
                earlyResult = RestoreCoordinatorPrepareResult.Failed(
                    RestoreCoordinatorFailure.TEMPORARY_CLEANUP_FAILED,
                )
            }
        }
        earlyResult ?: requireNotNull(coreResult).toCoordinatorResult()
    }

    suspend fun restore(
        prepared: AndroidPreparedRestore,
        policy: RestoreMergePolicy,
        cancellationSignal: RestoreCancellationSignal = NeverCancelRestore,
        progressListener: RestoreProgressListener = RestoreProgressListener { },
    ): RestoreResult = engine.restore(
        prepared = prepared.core,
        policy = policy,
        cancellationSignal = cancellationSignal,
        progressListener = progressListener,
    )

    suspend fun recoverInterruptedOperations(): Int = engine.recoverInterruptedOperations()

    private suspend fun sniff(reference: RestoreSafReference): RestoreInputKind? =
        safAccess.open(reference).use { source ->
            val prefix = ByteArray(SNIFF_BYTES)
            var count = 0
            while (count < prefix.size) {
                currentCoroutineContext().ensureActive()
                val read = source.read(prefix, count, prefix.size - count)
                if (read < 0) break
                if (read == 0) {
                    val single = source.read()
                    if (single < 0) break
                    prefix[count++] = single.toByte()
                } else {
                    count += read
                }
            }
            when {
                count >= ENCRYPTED_MAGIC.size && prefix.startsWith(ENCRYPTED_MAGIC) ->
                    RestoreInputKind.ENCRYPTED
                count >= ZIP_MAGIC.size && prefix.startsWith(ZIP_MAGIC) -> RestoreInputKind.ZIP
                else -> null
            }
        }

    private suspend fun copyPlainArchive(reference: RestoreSafReference, destination: File) {
        safAccess.open(reference).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied = checkedCopyLength(copied, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
    }

    private suspend fun decryptArchive(
        reference: RestoreSafReference,
        destination: File,
        password: CharArray,
    ) {
        val passwordCopy = password.copyOf()
        val job = currentCoroutineContext()[Job]
        try {
            safAccess.open(reference).use { source ->
                FileOutputStream(destination).use { destinationStream ->
                    EncryptedBackupEnvelope.decrypt(
                        encryptedSource = CancellationCheckingInputStream(source) {
                            job?.ensureActive()
                        },
                        plaintextZipDestination = CancellationCheckingOutputStream(
                            destination = destinationStream,
                            checkCancelled = { job?.ensureActive() },
                        ),
                        password = passwordCopy,
                    )
                    destinationStream.flush()
                }
            }
        } finally {
            passwordCopy.fill('\u0000')
        }
    }

    private fun checkedCopyLength(current: Long, count: Int): Long {
        val updated = try {
            Math.addExact(current, count.toLong())
        } catch (failure: ArithmeticException) {
            throw IOException("The restore archive is too large.", failure)
        }
        if (updated > MAXIMUM_STAGED_ARCHIVE_BYTES) {
            throw IOException("The restore archive exceeds the supported limit.")
        }
        return updated
    }

    private companion object {
        val ENCRYPTED_MAGIC = "RMEENC01".toByteArray(Charsets.US_ASCII)
        val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        const val SNIFF_BYTES = 8
        const val COPY_BUFFER_BYTES = 32 * 1024
        const val MAXIMUM_STAGED_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

@JvmInline
internal value class RestoreSafReference(val value: String)

internal interface RestoreSafAccess {
    fun open(reference: RestoreSafReference): InputStream
}

private class AndroidRestoreSafAccess(context: Context) : RestoreSafAccess {
    private val resolver = context.contentResolver

    override fun open(reference: RestoreSafReference): InputStream =
        resolver.openInputStream(Uri.parse(reference.value)) ?: throw FileNotFoundException()
}

private class CancellationCheckingInputStream(
    source: InputStream,
    private val checkCancelled: () -> Unit,
) : FilterInputStream(BufferedInputStream(source)) {
    override fun read(): Int {
        checkCancelled()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancelled()
        return super.read(buffer, offset, length)
    }
}

private class CancellationCheckingOutputStream(
    destination: OutputStream,
    private val checkCancelled: () -> Unit,
    private val maximumBytes: Long = 8L * 1024L * 1024L * 1024L,
) : FilterOutputStream(destination) {
    private var writtenBytes = 0L

    override fun write(value: Int) {
        checkCancelled()
        requireCapacity(1)
        out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkCancelled()
        requireCapacity(length)
        out.write(buffer, offset, length)
    }

    private fun requireCapacity(length: Int) {
        val updated = try {
            Math.addExact(writtenBytes, length.toLong())
        } catch (failure: ArithmeticException) {
            throw IOException("The decrypted restore archive is too large.", failure)
        }
        if (updated > maximumBytes) throw IOException("The decrypted restore archive is too large.")
        writtenBytes = updated
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun BackupEnvelopeException.toCoordinatorFailure(): RestoreCoordinatorFailure = when (failure) {
    EnvelopeFailure.AUTHENTICATION_FAILED ->
        RestoreCoordinatorFailure.WRONG_PASSWORD_OR_DAMAGED_ENCRYPTED_BACKUP
    EnvelopeFailure.CRYPTO_UNAVAILABLE -> RestoreCoordinatorFailure.CRYPTO_UNAVAILABLE
    else -> RestoreCoordinatorFailure.INVALID_ENCRYPTED_BACKUP
}

private fun RestorePreparationResult.toCoordinatorResult(): RestoreCoordinatorPrepareResult = when (this) {
    is RestorePreparationResult.Ready -> RestoreCoordinatorPrepareResult.Ready(
        AndroidPreparedRestore(prepared),
    )
    is RestorePreparationResult.Failed -> RestoreCoordinatorPrepareResult.Failed(
        reason = when (reason) {
            RestorePreparationFailure.INSUFFICIENT_STORAGE ->
                RestoreCoordinatorFailure.INSUFFICIENT_STORAGE
            RestorePreparationFailure.INVALID_OR_CORRUPT_BACKUP ->
                RestoreCoordinatorFailure.INVALID_OR_CORRUPT_BACKUP
            RestorePreparationFailure.STAGING_UNAVAILABLE ->
                RestoreCoordinatorFailure.TEMPORARY_STORAGE_UNAVAILABLE
            RestorePreparationFailure.LIBRARY_UNAVAILABLE ->
                RestoreCoordinatorFailure.LIBRARY_UNAVAILABLE
        },
        detailCode = formatFailure,
    )
}
