package org.synapseworks.pageharbor.pptx
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
class PptxInstrumentedTest {
    @Test fun queueFormat4UpgradeAndLegacyRefusal(){InstrumentationRegistry.getInstrumentation().pptxReaderChecks()}
    @Test fun reviewedRecipeLocalSaveAndRecovery(){InstrumentationRegistry.getInstrumentation().pptxRecipeChecks()}
    @Test fun independentObjectsAndResourceBounds(){InstrumentationRegistry.getInstrumentation().pptxGenerationChecks()}
    @Test fun actualReviewSaveShareAndOwnership(){InstrumentationRegistry.getInstrumentation().pptxSaveChecks()}
}
