package org.synapseworks.pageharbor.docx
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
class DocxRestartInstrumentedTest {
    @Test fun actualDifferentProcessReconcilesFrozenDocx(){InstrumentationRegistry.getInstrumentation().docxRestartChecks()}
}
