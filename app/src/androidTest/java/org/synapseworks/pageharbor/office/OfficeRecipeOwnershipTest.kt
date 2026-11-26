package org.synapseworks.pageharbor.office

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.workflow.*

class OfficeRecipeOwnershipTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unknownProviderCannotCreateOfferOrAcquireRecipeSourceGrant() {
        assumeTrue(BuildConfig.M35_OFFICE_ENABLED)
        val source=File(app.cacheDir,"shared-pdfs/offer-${UUID.randomUUID()}.docx").apply{parentFile!!.mkdirs();writeText("synthetic unsupported source")}
        val output=File(app.cacheDir,"offer-${UUID.randomUUID()}.pdf").apply{writeText("synthetic bounded output")}
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",source)
        val before=app.contentResolver.persistedUriPermissions.map{it.uri.toString()}.toSet()
        try {
            OfficeSource.open(app,uri,"docx",{}).use { acquired->
                assertFalse(WorkflowDestination(app).localSource(uri,acquired.descriptor))
                assertThrows(IllegalArgumentException::class.java) {
                    OfficeRecipeOffers.create(app,UUID.randomUUID().toString(),acquired,OfficeOutput(output,officeDigest(output) {},1,UUID.randomUUID().toString())) {}
                }
                assertEquals("synthetic unsupported source",source.readText())
            }
            assertEquals(before,app.contentResolver.persistedUriPermissions.map{it.uri.toString()}.toSet())
        } finally { source.delete();output.delete() }
    }
    @Test fun staleAndForeignOffersCannotBeClaimed() {
        assertNull(OfficeRecipeOffers.claim(UUID.randomUUID().toString(),UUID.randomUUID().toString()))
        OfficeRecipeOffers.discard(UUID.randomUUID().toString())
    }
    @Test fun unprovisionedReaderPreservesOfficeMetadataAndFiles() {
        org.junit.Assume.assumeFalse(BuildConfig.M35_OFFICE_ENABLED)
        val root=File(app.noBackupFilesDir,"office-reader-${UUID.randomUUID()}").apply{mkdirs()}
        val id=UUID.randomUUID().toString();val file=File(root,"$id.pdf").apply{writeText("synthetic retained bytes")}
        val metadata=File(root,"queue.json").apply{writeText("""{"version":5,"recipes":[{"id":"$id","version":1,"label":"Synthetic","preset":"OFFICE","template":"{custom}-{counter}","custom":"Synthetic","tree":null,"nextCounter":1}],"jobs":[],"grants":[]}""")}
        val bytes=metadata.readBytes()
        val store=WorkflowStore(root)
        try { assertFalse(store.healthy);assertArrayEquals(bytes,metadata.readBytes());assertTrue(file.isFile) }
        finally { store.close();root.deleteRecursively() }
    }
}
