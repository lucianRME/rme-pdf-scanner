package org.synapseworks.pageharbor.document.searchablepdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchablePdfFailureMappingTest {
    @Test
    fun oversizedPageGenerationFailureRemainsTypedDuringPreparation() {
        assertEquals(
            SearchablePdfPreparationError.SOURCE_TOO_LARGE,
            searchablePreparationErrorForGenerationFailure(
                SearchablePdfGenerationError.PAGE_IMAGE_TOO_LARGE,
            ),
        )
    }

    @Test
    fun unrelatedGenerationFailureRemainsGeneric() {
        assertEquals(
            SearchablePdfPreparationError.GENERATION_FAILED,
            searchablePreparationErrorForGenerationFailure(
                SearchablePdfGenerationError.PAGE_IMAGE_UNREADABLE,
            ),
        )
    }
}
