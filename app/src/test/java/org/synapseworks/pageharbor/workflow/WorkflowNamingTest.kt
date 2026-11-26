package org.synapseworks.pageharbor.workflow

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorkflowNamingTest {
    private val at=Instant.parse("2026-11-08T00:30:00Z")
    private fun name(custom:String,template:String="{custom}-{date}-{counter}",zone:String="UTC",counter:Int=1)=WorkflowNaming.name(template,custom,at,ZoneId.of(zone),counter)
    @Test fun frozenIndependentDateZoneAndCounterOracle() {
        assertEquals("Report-2026-11-08-000001.pdf",name("Report"))
        assertEquals("Report-2026-11-07-000001.pdf",name("Report",zone="America/Los_Angeles"))
        assertEquals("Café.pdf",name("Cafe\u0301",template="{custom}",counter=99))
        assertEquals("Report-2026-11-08-999999.pdf",name("Report",counter=999999))
    }
    @Test fun unsafeComponentsAndByteBoundsRefuseWithoutSanitizing() {
        for(custom in listOf("../secret","a/b","a\\b","CON","x.pdf","x\u202ey","x\n","a:","..","","x".repeat(117),"LPT9","AUX"," name","name.","😀".repeat(30))) {
            assertThrows(IllegalArgumentException::class.java){name(custom)}
        }
        assertTrue(name("é".repeat(45)).toByteArray().size<=120)
    }
    @Test fun docxExtensionHasIndependentByteLimitAndFrozenCounter() {
        val name=WorkflowNaming.name("{custom}-{date}-{counter}","Café",at,ZoneId.of("UTC"),7,"docx")
        assertEquals("Café-2026-11-08-000007.docx",name);WorkflowNaming.verifyExported(name,"docx")
        assertThrows(IllegalArgumentException::class.java){WorkflowNaming.verifyExported(name)}
        assertThrows(IllegalArgumentException::class.java){WorkflowNaming.name("{custom}","a".repeat(116),at,ZoneId.of("UTC"),1,"docx")}
        assertTrue(WorkflowPreset.DOCX.excludesSources);assertEquals("docx",WorkflowPreset.DOCX.extension)
    }
    @Test fun unknownTokensAndCounterExhaustionDecline() {
        for(template in listOf("{year}","{custom}.pdf","{CUSTOM}","folder/{custom}",""))assertThrows(IllegalArgumentException::class.java){name("Title",template)}
        for(counter in listOf(-1,0,1000000))assertThrows(IllegalArgumentException::class.java){name("Title",counter=counter)}
    }
    @Test fun archivalAndProvisionedOfficeProfilesRetainDifferentOutputMeaningAndUncertainStates() {
        assertTrue(WorkflowPreset.ARCHIVAL.available);assertTrue(WorkflowPreset.ARCHIVAL.label.contains("image-only"));assertEquals(org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED,WorkflowPreset.OFFICE.available)
        assertTrue(WorkflowPreset.OFFICE.excludesSources);assertEquals("pdf",WorkflowPreset.OFFICE.extension);assertEquals("application/pdf",WorkflowPreset.OFFICE.mime)
        assertTrue(WorkflowPreset.OFFICE.label.contains("reviewed local source"))
        assertTrue(WorkflowStatus.UNCERTAIN.pending());assertTrue(WorkflowStatus.COLLISION.pending())
        assertFalse(WorkflowStatus.SAVED.pending());assertFalse(WorkflowStatus.CANCELLED.pending())
    }
}
