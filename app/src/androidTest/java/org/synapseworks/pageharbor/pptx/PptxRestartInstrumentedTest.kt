package org.synapseworks.pageharbor.pptx
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
/** Run separately after terminating the first synthetic test process. */
class PptxRestartInstrumentedTest {
    @Test fun differentProcessReconcilesFrozenPresentation(){InstrumentationRegistry.getInstrumentation().pptxRestartChecks()}
}
