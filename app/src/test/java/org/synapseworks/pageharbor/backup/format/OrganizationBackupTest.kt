package org.synapseworks.pageharbor.backup.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class OrganizationBackupTest {
    private val fixture=BackupFormatTestFixture()
    private val manifest=fixture.manifest.copy(formatVersion=8,minimumReaderVersion=8,ocr=BackupOcrManifest.empty(),tags=listOf(BackupTagRecord("tag-one","Receipts"),BackupTagRecord("unused","Archive")))
    private val document=fixture.documents.single().copy(favorite=true,tagIds=listOf("tag-one"))
    @Test fun manifestAndDocumentRoundTripPreserveUnusedCatalogAndStableReferences() {
        val output=ByteArrayOutputStream();BackupJsonCodec.writeManifest(manifest,output)
        val decoded=BackupJsonCodec.readManifest(ByteArrayInputStream(output.toByteArray()))
        assertEquals(manifest,decoded)
        assertEquals(document,BackupJsonCodec.decodeDocument(BackupJsonCodec.encodeDocument(document),BackupFormatLimits()))
        validate(manifest,document)
        val old=BackupJsonCodec.decodeDocument(BackupJsonCodec.encodeDocument(document.copy(favorite=false,tagIds=emptyList())).replace(",\"favorite\":false,\"tagIds\":[]",""),BackupFormatLimits())
        assertFalse(old.favorite);assertTrue(old.tagIds.isEmpty())
        BackupFormatValidator.validateDocumentOrganization(old,7,emptySet())
        assertThrows(BackupFormatException::class.java){validate(manifest,old)}
    }
    @Test fun malformedDuplicateOrOrphanMetadataCannotBeSilentlyDropped() {
        listOf(document.copy(tagIds=listOf("absent")),document.copy(tagIds=listOf("tag-one","tag-one")),document.copy(tagIds=(1..33).map{"tag-$it"})).forEach {
            assertThrows(BackupFormatException::class.java){validate(manifest,it)}
        }
        listOf(listOf(BackupTagRecord("a","Receipts"),BackupTagRecord("b","RECEIPTS")),listOf(BackupTagRecord("a"," Receipts")),listOf(BackupTagRecord("a","A"),BackupTagRecord("a","B"))).forEach {
            assertThrows(BackupFormatException::class.java){BackupFormatValidator.validateCompatibility(manifest.copy(tags=it))}
        }
        assertThrows(BackupFormatException::class.java){BackupJsonCodec.decodeDocument(BackupJsonCodec.encodeDocument(document).replace("\"favorite\":true","\"favorite\":null"),BackupFormatLimits())}
        val out=ByteArrayOutputStream();BackupJsonCodec.writeManifest(manifest,out)
        assertThrows(BackupFormatException::class.java){BackupJsonCodec.readManifest(ByteArrayInputStream(out.toByteArray().toString(Charsets.UTF_8).replace("\"tags\":[", "\"tags\":null,\"ignored\":[").toByteArray()))}
    }
    @Test fun exactPreviousReaderRejectsFormatEightAndLegacyCannotCarryOrganization() {
        val e=assertThrows(BackupFormatException::class.java){M16BackupFormatValidator.validateCompatibility(manifest)}
        assertEquals(BackupFormatFailure.UNSUPPORTED_VERSION,e.failure)
        assertThrows(BackupFormatException::class.java){validate(manifest.copy(formatVersion=7,minimumReaderVersion=7),document)}
        BackupFormatValidator.validateCompatibility(manifest.copy(formatVersion=7,minimumReaderVersion=7,tags=emptyList()))
    }
    private fun validate(m:BackupManifest,d:BackupDocumentRecord)=BackupFormatValidator.validateCompleteBackup(m,fixture.folders,listOf(d),fixture.pages,fixture.sourceAssets)
}
