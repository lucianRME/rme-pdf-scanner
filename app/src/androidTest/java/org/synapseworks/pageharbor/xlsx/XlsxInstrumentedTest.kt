package org.synapseworks.pageharbor.xlsx
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
class XlsxInstrumentedTest {
    @Test fun readerUpgradeAndLegacyRefusal(){InstrumentationRegistry.getInstrumentation().xlsxReaderChecks()}
    @Test fun recipeFrozenLocalSaveAndRecovery(){InstrumentationRegistry.getInstrumentation().xlsxRecipeChecks()}
    @Test fun frozenGenerationTypesAndAutomaticReceiptEvidence(){InstrumentationRegistry.getInstrumentation().xlsxGenerationChecks()}
    @Test fun actualReviewSaveShareSourceProtectionAndCancellation(){InstrumentationRegistry.getInstrumentation().xlsxSaveChecks()}
}
