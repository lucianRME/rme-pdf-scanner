package org.synapseworks.pageharbor.backup.format

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolingZipWriterTest {
    @Test
    fun standardArchiveIsReadableByZipInputStreamAndZipFileWithUtf8Name() = withWorkspace { root ->
        val archive = File(root, "standard.zip")
        val central = File(root, "standard-central.bin")
        val path = "metadata/über-日本語.txt"
        val bytes = "local-only UTF-8 payload".toByteArray()

        writeArchive(archive, central) { zip ->
            zip.putNextEntry(path, bytes.size.toLong())
            zip.write(bytes)
            zip.closeEntry()
        }

        ZipInputStream(archive.inputStream()).use { zip ->
            assertEquals(path, requireNotNull(zip.nextEntry).name)
            assertArrayEquals(bytes, zip.readBytes())
            assertNull(zip.nextEntry)
        }
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry(path))
            assertEquals(java.util.zip.ZipEntry.DEFLATED, entry.method)
            assertArrayEquals(bytes, zip.getInputStream(entry).use { it.readBytes() })
        }
        assertFalse(central.exists())
    }

    @Test
    fun forcedZip64EndRecordsRemainReadableByStandardReaders() = withWorkspace { root ->
        val archive = File(root, "zip64.zip")
        val central = File(root, "zip64-central.bin")
        FileOutputStream(archive).use { output ->
            SpoolingZipWriter(
                destination = output,
                centralDirectoryFile = central,
                zip64EndEntryThreshold = 2,
            ).use { zip ->
                zip.putNextEntry("first.txt", 16)
                zip.write("first".toByteArray())
                zip.closeEntry()
                zip.putNextEntry("second.txt", 16)
                zip.write("second".toByteArray())
                zip.closeEntry()
                zip.finish()
            }
        }

        val raw = archive.readBytes()
        assertTrue(raw.containsSignature(0x50, 0x4b, 0x06, 0x06))
        assertTrue(raw.containsSignature(0x50, 0x4b, 0x06, 0x07))
        ZipInputStream(archive.inputStream()).use { zip ->
            assertEquals("first.txt", requireNotNull(zip.nextEntry).name)
            assertEquals("first", zip.readBytes().toString(Charsets.UTF_8))
            assertEquals("second.txt", requireNotNull(zip.nextEntry).name)
            assertEquals("second", zip.readBytes().toString(Charsets.UTF_8))
            assertNull(zip.nextEntry)
        }
        ZipFile(archive).use { zip ->
            assertEquals(2, zip.size())
            assertEquals("first", zip.getInputStream(requireNotNull(zip.getEntry("first.txt"))).use {
                it.readBytes().toString(Charsets.UTF_8)
            })
        }
        assertFalse(central.exists())
    }

    @Test
    fun forcedZip64EntryRecordRemainsReadableByStandardReaders() = withWorkspace { root ->
        val archive = File(root, "zip64-entry.zip")
        val central = File(root, "zip64-entry-central.bin")
        val payload = "zip64-entry-payload".toByteArray()
        FileOutputStream(archive).use { output ->
            SpoolingZipWriter(
                destination = output,
                centralDirectoryFile = central,
                zip64EntryValueThreshold = 1,
            ).use { zip ->
                zip.putNextEntry("entry.txt", payload.size.toLong())
                zip.write(payload)
                zip.closeEntry()
                zip.finish()
            }
        }

        ZipInputStream(archive.inputStream()).use { zip ->
            assertEquals("entry.txt", requireNotNull(zip.nextEntry).name)
            assertArrayEquals(payload, zip.readBytes())
            assertNull(zip.nextEntry)
        }
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("entry.txt"))
            assertArrayEquals(payload, zip.getInputStream(entry).use { it.readBytes() })
        }
        assertFalse(central.exists())
    }

    @Test
    fun twentyThousandEntriesUseDiskCentralDirectoryAndRemainReadable() = withWorkspace { root ->
        val archive = File(root, "many.zip")
        val central = File(root, "many-central.bin")

        writeArchive(archive, central) { zip ->
            repeat(20_000) { index ->
                zip.putNextEntry("entries/${index.toString().padStart(5, '0')}.txt", 0)
                zip.closeEntry()
            }
        }

        ZipFile(archive).use { zip ->
            assertEquals(20_000, zip.size())
            assertNotNull(zip.getEntry("entries/00000.txt"))
            assertNotNull(zip.getEntry("entries/19999.txt"))
        }
        assertFalse(central.exists())
    }

    @Test
    fun cancellationDuringCentralDirectoryCopyDeletesOperationSpool() = withWorkspace { root ->
        val archive = File(root, "cancelled.zip")
        val central = File(root, "cancelled-central.bin")
        val cancelled = AtomicBoolean(false)
        val output = FileOutputStream(archive)
        val zip = SpoolingZipWriter(
            destination = output,
            cancellationCheck = {
                if (cancelled.get()) throw CancellationException("synthetic cancellation")
            },
            centralDirectoryFile = central,
        )
        try {
            repeat(100) { index ->
                zip.putNextEntry("entry-$index.txt", 0)
                zip.closeEntry()
            }
            cancelled.set(true)

            assertThrows(CancellationException::class.java, zip::finish)
        } finally {
            zip.close()
            output.close()
        }

        assertFalse(central.exists())
    }

    @Test
    fun cleanupFailureIsObservableAndCloseCanRetry() = withWorkspace { root ->
        val archive = File(root, "cleanup-retry.zip")
        val central = File(root, "cleanup-retry-central.bin")
        var allowCleanup = false
        var cleanupAttempts = 0
        val output = FileOutputStream(archive)
        val zip = SpoolingZipWriter(
            destination = output,
            centralDirectoryFile = central,
            cleanupCentralDirectory = { file ->
                cleanupAttempts += 1
                allowCleanup && (!file.exists() || file.delete())
            },
        )
        zip.putNextEntry("entry.txt", 0)
        zip.closeEntry()

        assertThrows(java.io.IOException::class.java, zip::close)
        assertEquals(3, cleanupAttempts)
        assertTrue(central.exists())

        allowCleanup = true
        zip.close()
        output.close()

        assertEquals(4, cleanupAttempts)
        assertFalse(central.exists())
    }

    private fun writeArchive(
        archive: File,
        central: File,
        write: (SpoolingZipWriter) -> Unit,
    ) {
        FileOutputStream(archive).use { output ->
            SpoolingZipWriter(output, centralDirectoryFile = central).use { zip ->
                write(zip)
                zip.finish()
            }
        }
    }

    private fun withWorkspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rme-spooling-zip-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun ByteArray.containsSignature(vararg signature: Int): Boolean = indices.any { start ->
    start + signature.size <= size && signature.indices.all { offset ->
        this[start + offset] == signature[offset].toByte()
    }
}
