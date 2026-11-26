package org.synapseworks.pageharbor.workflow

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class ArchivalInstrumentedTest {
    @Test fun archivalAliasesFaultsCancellationLockAndFrozenSourceOwnership() { InstrumentationRegistry.getInstrumentation().archivalFaultChecks() }
    @Test fun actualArchivalReviewAndTreeManualSave() { InstrumentationRegistry.getInstrumentation().workflowSaveChecks(true) }
    @Test fun archivalRestartReconcilesFrozenCopy() { InstrumentationRegistry.getInstrumentation().workflowRestartChecks(true) }
    @Test fun frozenAppearanceColorGeometryAndImageOnlyResources() { InstrumentationRegistry.getInstrumentation().archivalGenerationChecks() }
}
