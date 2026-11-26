package org.synapseworks.pageharbor.xlsx
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
class XlsxRestartInstrumentedTest {
    @Test fun actualDifferentProcessReconcilesWorkbook(){InstrumentationRegistry.getInstrumentation().xlsxRestartChecks()}
}
