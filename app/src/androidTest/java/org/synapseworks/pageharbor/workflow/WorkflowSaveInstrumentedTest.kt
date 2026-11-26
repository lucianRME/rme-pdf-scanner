package org.synapseworks.pageharbor.workflow

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class WorkflowSaveInstrumentedTest {
    @Test fun actualTreeAndManualSaveReviewCollisionCancellationAndRevocation() { InstrumentationRegistry.getInstrumentation().workflowSaveChecks() }
    @Test fun restartedProcessReconcilesExactInterruptedOutput() { InstrumentationRegistry.getInstrumentation().workflowRestartChecks() }
}
