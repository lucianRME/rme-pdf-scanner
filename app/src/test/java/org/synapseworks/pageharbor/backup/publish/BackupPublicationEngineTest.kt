package org.synapseworks.pageharbor.backup.publish

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.backup.engine.LibraryBackupStorageEstimate
import org.synapseworks.pageharbor.backup.engine.VerifiedLibraryBackupArtifact
import org.synapseworks.pageharbor.backup.format.BackupArchiveWriter
import org.synapseworks.pageharbor.backup.format.BackupFormatTestFixture

class BackupPublicationEngineTest {
    @Test
    fun `unencrypted publication reopens and verifies final destination`() = withArtifact { artifact ->
        val destination = MemoryDestination()

        val result = runBlocking {
            BackupPublicationEngine().publishUnencrypted(artifact, destination)
        }

        result as BackupPublicationResult.Verified
        assertEquals(PublishedBackupKind.UNENCRYPTED_ZIP, result.backup.kind)
        assertEquals(artifact.manifest, result.backup.manifest)
        assertFalse(destination.deleted)
    }

    @Test
    fun `encrypted publication reopens authenticates and matches complete inner zip`() =
        withArtifact { artifact ->
            val destination = MemoryDestination()
            val password = "portable backup password".toCharArray()
            try {
                val result = runBlocking {
                    BackupPublicationEngine().publishEncrypted(artifact, destination, password)
                }

                result as BackupPublicationResult.Verified
                assertEquals(PublishedBackupKind.ENCRYPTED_ENVELOPE_V1, result.backup.kind)
                assertEquals(artifact.sizeBytes, result.backup.plaintextBytes)
                assertFalse(destination.deleted)
                assertFalse(destination.bytes.toByteArray().containsSubsequence("Synthetic backup".toByteArray()))
            } finally {
                password.fill('\u0000')
            }
        }

    @Test
    fun `unverifiable destination is deleted and never reported as verified`() = withArtifact { artifact ->
        val destination = MemoryDestination(tamperOnRead = true)

        val result = runBlocking {
            BackupPublicationEngine().publishUnencrypted(artifact, destination)
        }

        result as BackupPublicationResult.Failed
        assertEquals(BackupPublicationFailure.DESTINATION_VERIFICATION_FAILED, result.reason)
        assertTrue(destination.deleted)
        assertTrue(result.destinationCleanupSucceeded)
    }

    private fun withArtifact(block: (VerifiedLibraryBackupArtifact) -> Unit) {
        val fixture = BackupFormatTestFixture()
        val root = Files.createTempDirectory("rme-publication-test").toFile()
        val file = File(root, "verified.zip")
        try {
            file.outputStream().use { output ->
                BackupArchiveWriter.write(output, fixture.manifest, fixture.records, fixture.assets)
            }
            val artifact = VerifiedLibraryBackupArtifact(
                file = file,
                manifest = fixture.manifest,
                sizeBytes = file.length(),
                storageEstimate = LibraryBackupStorageEstimate(0, 0, 0, 0, null),
                deleteArtifact = File::delete,
            )
            block(artifact)
        } finally {
            root.deleteRecursively()
        }
    }

    private class MemoryDestination(
        private val tamperOnRead: Boolean = false,
    ) : BackupPublicationDestination {
        var bytes = ByteArrayOutputStream()
        var deleted = false

        override fun openOutput(): OutputStream {
            bytes = ByteArrayOutputStream()
            return bytes
        }

        override fun openInput(): InputStream {
            val value = bytes.toByteArray()
            if (tamperOnRead && value.isNotEmpty()) value[value.lastIndex / 2] =
                (value[value.lastIndex / 2].toInt() xor 1).toByte()
            return ByteArrayInputStream(value)
        }

        override fun delete(): Boolean {
            deleted = true
            bytes.reset()
            return true
        }
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    return indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset ->
            this[start + offset] == candidate[offset]
        }
    }
}
