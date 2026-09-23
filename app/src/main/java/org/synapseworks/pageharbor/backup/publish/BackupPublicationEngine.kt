package org.synapseworks.pageharbor.backup.publish

import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.synapseworks.pageharbor.backup.crypto.EncryptedBackupEnvelope
import org.synapseworks.pageharbor.backup.format.BackupArchiveReader
import org.synapseworks.pageharbor.backup.format.BackupStagingSink
import org.synapseworks.pageharbor.backup.format.BackupManifest
import org.synapseworks.pageharbor.backup.engine.VerifiedLibraryBackupArtifact

interface BackupPublicationDestination {
    fun openOutput(): OutputStream?

    fun openInput(): InputStream?

    /** Removes an incomplete or unverifiable destination artifact. */
    fun delete(): Boolean
}

enum class PublishedBackupKind {
    UNENCRYPTED_ZIP,
    ENCRYPTED_ENVELOPE_V1,
}

data class VerifiedPublishedBackup(
    val manifest: BackupManifest,
    val kind: PublishedBackupKind,
    val plaintextBytes: Long,
)

sealed interface BackupPublicationResult {
    data class Verified(val backup: VerifiedPublishedBackup) : BackupPublicationResult

    data class Failed(
        val reason: BackupPublicationFailure,
        val destinationCleanupSucceeded: Boolean,
    ) : BackupPublicationResult
}

enum class BackupPublicationFailure {
    DESTINATION_UNAVAILABLE,
    COPY_FAILED,
    DESTINATION_VERIFICATION_FAILED,
}

/** Copies a verified private artifact to SAF and verifies the bytes at the final destination. */
class BackupPublicationEngine {
    suspend fun publishUnencrypted(
        artifact: VerifiedLibraryBackupArtifact,
        destination: BackupPublicationDestination,
    ): BackupPublicationResult = publish(destination) {
        val output = destination.openOutput()
            ?: return@publish BackupPublicationResult.Failed(
                BackupPublicationFailure.DESTINATION_UNAVAILABLE,
                destination.delete(),
            )
        val coroutineContext = currentCoroutineContext()
        artifact.file.inputStream().use { rawSource ->
            val source = CancellableInputStream(rawSource, coroutineContext)
            output.use { target -> copyCancellable(source, target) }
        }
        val source = destination.openInput()
            ?: return@publish BackupPublicationResult.Failed(
                BackupPublicationFailure.DESTINATION_UNAVAILABLE,
                destination.delete(),
            )
        val verified = source.use { input ->
            BackupArchiveReader.readAndVerify(
                CancellableInputStream(input, coroutineContext),
                DiscardingStagingSink(coroutineContext),
            )
        }
        BackupPublicationResult.Verified(
            VerifiedPublishedBackup(
                manifest = verified.manifest,
                kind = PublishedBackupKind.UNENCRYPTED_ZIP,
                plaintextBytes = artifact.sizeBytes,
            ),
        )
    }

    suspend fun publishEncrypted(
        artifact: VerifiedLibraryBackupArtifact,
        destination: BackupPublicationDestination,
        password: CharArray,
    ): BackupPublicationResult = publish(destination) {
        val output = destination.openOutput()
            ?: return@publish BackupPublicationResult.Failed(
                BackupPublicationFailure.DESTINATION_UNAVAILABLE,
                destination.delete(),
            )
        val coroutineContext = currentCoroutineContext()
        val writtenSummary = artifact.file.inputStream().use { rawSource ->
            val source = CancellableInputStream(rawSource, coroutineContext)
            output.use { target ->
                EncryptedBackupEnvelope.encrypt(source, target, password)
            }
        }
        currentCoroutineContext().ensureActive()
        val source = destination.openInput()
            ?: return@publish BackupPublicationResult.Failed(
                BackupPublicationFailure.DESTINATION_UNAVAILABLE,
                destination.delete(),
            )
        val reopenedSummary = source.use { input ->
            EncryptedBackupEnvelope.decrypt(
                CancellableInputStream(input, coroutineContext),
                CancellableDiscardingOutputStream(coroutineContext),
                password,
            )
        }
        val exactMatch = writtenSummary.plaintextBytes == reopenedSummary.plaintextBytes &&
            writtenSummary.dataFrameCount == reopenedSummary.dataFrameCount &&
            MessageDigest.isEqual(
                writtenSummary.plaintextSha256,
                reopenedSummary.plaintextSha256,
            )
        writtenSummary.plaintextSha256.fill(0)
        reopenedSummary.plaintextSha256.fill(0)
        if (!exactMatch) {
            BackupPublicationResult.Failed(
                BackupPublicationFailure.DESTINATION_VERIFICATION_FAILED,
                destination.delete(),
            )
        } else {
            BackupPublicationResult.Verified(
                VerifiedPublishedBackup(
                    manifest = artifact.manifest,
                    kind = PublishedBackupKind.ENCRYPTED_ENVELOPE_V1,
                    plaintextBytes = reopenedSummary.plaintextBytes,
                ),
            )
        }
    }

    private suspend inline fun publish(
        destination: BackupPublicationDestination,
        block: suspend () -> BackupPublicationResult,
    ): BackupPublicationResult = try {
        currentCoroutineContext().ensureActive()
        block()
    } catch (cancelled: CancellationException) {
        destination.delete()
        throw cancelled
    } catch (_: Exception) {
        BackupPublicationResult.Failed(
            BackupPublicationFailure.DESTINATION_VERIFICATION_FAILED,
            destination.delete(),
        )
    }

    private suspend fun copyCancellable(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            if (count > 0) destination.write(buffer, 0, count)
        }
        destination.flush()
    }
}

private class DiscardingStagingSink(
    private val coroutineContext: CoroutineContext,
) : BackupStagingSink {
    override fun open(relativePath: String): OutputStream =
        CancellableDiscardingOutputStream(coroutineContext)

    override fun verified() = Unit

    override fun abort() = Unit
}

private class CancellableDiscardingOutputStream(
    private val coroutineContext: CoroutineContext,
) : OutputStream() {
    override fun write(value: Int) {
        coroutineContext.ensureActive()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        coroutineContext.ensureActive()
    }
}

private class CancellableInputStream(
    source: InputStream,
    private val coroutineContext: CoroutineContext,
) : FilterInputStream(source) {
    override fun read(): Int {
        coroutineContext.ensureActive()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        coroutineContext.ensureActive()
        return super.read(buffer, offset, length)
    }
}

private const val COPY_BUFFER_BYTES = 64 * 1024
