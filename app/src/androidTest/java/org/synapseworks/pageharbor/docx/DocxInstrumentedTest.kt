package org.synapseworks.pageharbor.docx
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
class DocxInstrumentedTest {
    @Test fun reviewedRecipeFrozenDocxAndLocalSave(){InstrumentationRegistry.getInstrumentation().docxRecipeChecks()}
    @Test fun actualMeasuredLayoutAndFitRejection(){InstrumentationRegistry.getInstrumentation().docxLayoutUiChecks()}
    @Test fun frozenAndroidGenerationAndSeparateOpticalEvidence(){InstrumentationRegistry.getInstrumentation().docxGenerationChecks()}
    @Test fun actualReviewSaveCancellationAliasesAndStaleLifecycle(){InstrumentationRegistry.getInstrumentation().docxSaveChecks()}
}
