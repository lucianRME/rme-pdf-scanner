package org.synapseworks.pageharbor.office

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.synapseworks.pageharbor.workflow.officeRecipeChecks

class OfficeRecipeActivityTest {
    @Test fun actualOpenReviewedHandoffQueueCancelAndSave() {
        assumeTrue(org.synapseworks.pageharbor.BuildConfig.M35_OFFICE_ENABLED)
        InstrumentationRegistry.getInstrumentation().officeRecipeChecks(checkNotNull(InstrumentationRegistry.getArguments().getString("m35RecipeSource")))
    }
}
