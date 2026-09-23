package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveRoundTripTest {
    @Test
    fun streamingInspectorRemeasuresJpegAndWebpDimensions() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe0.toByte(), 0x00, 0x04, 0x00, 0x00,
            0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08,
            0x00, 0x02,
            0x00, 0x03,
            0x01, 0x01, 0x11, 0x00,
        )
        val jpegCollector = BackupAssetInspection.Collector()
        jpegCollector.update(jpeg, 0, 7)
        jpegCollector.update(jpeg, 7, jpeg.size - 7)
        jpegCollector.finish().requireMatches("image/jpeg", 3, 2)

        val webp = ByteArray(30).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WEBP".toByteArray().copyInto(this, 8)
            "VP8X".toByteArray().copyInto(this, 12)
            this[24] = 2
            this[27] = 1
        }
        val webpCollector = BackupAssetInspection.Collector()
        webpCollector.update(webp, 0, webp.size)
        webpCollector.finish().requireMatches("image/webp", 3, 2)
    }

    @Test
    fun nestedTwentyOnePageDocumentAndOriginalPdfRoundTripThroughStaging() {
        val fixture = BackupFormatTestFixture(pageCount = 21)
        val archive = fixture.writeArchive()
        val staging = MemoryStagingSink()

        val verified = readAndVerify(archive, staging)

        assertEquals(2, verified.folders.size)
        assertEquals("folder-root", verified.folders.single { it.folderId == "folder-child" }.parentFolderId)
        assertEquals(21, verified.pages.size)
        assertEquals(1, verified.sourceAssets.size)
        assertTrue(staging.verified)
        assertFalse(staging.aborted)
        fixture.pageBytesByPath.forEach { (path, expected) ->
            assertArrayEquals(expected, staging.assets.getValue(path).toByteArray())
        }
        assertArrayEquals(
            fixture.sourceBytes,
            staging.assets.getValue(fixture.sourceAssets.single().relativePath).toByteArray(),
        )
    }

    @Test
    fun writerRejectsAssetBytesThatDoNotMatchMetadata() {
        val fixture = BackupFormatTestFixture()
        val output = ByteArrayOutputStream()
        val wrongAssets = BackupAssetStreamOpener { path ->
            if (path == fixture.pages.first().relativePath) {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            } else {
                fixture.assets.open(path)
            }
        }

        assertBackupFailure(BackupFormatFailure.SIZE_MISMATCH) {
            BackupArchiveWriter.write(output, fixture.manifest, fixture.records, wrongAssets)
        }
    }

    @Test
    fun pathRegistryRejectsExactAndCanonicalDuplicates() {
        val exact = BackupPathRegistry()
        exact.add(RME_BACKUP_MANIFEST_PATH)
        assertBackupFailure(BackupFormatFailure.DUPLICATE_ENTRY) {
            exact.add(RME_BACKUP_MANIFEST_PATH)
        }

        val canonical = BackupPathRegistry()
        canonical.add("documents/document-1/pages/0-page-1.png")
        assertBackupFailure(BackupFormatFailure.DUPLICATE_ENTRY) {
            canonical.add("documents/DOCUMENT-1/pages/0-page-1.png")
        }
    }
}
