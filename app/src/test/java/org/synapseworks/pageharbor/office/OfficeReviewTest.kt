package org.synapseworks.pageharbor.office

import org.junit.Assert.*
import org.junit.Test

class OfficeReviewTest {
    private val digest = "a".repeat(64)
    @Test fun onlyAllReviewedPagesOfTheSameImmutableCopyEnableExport() {
        val review = OfficeReview("source-owner", digest, 3)
        assertFalse(review.ready("source-owner", digest))
        review.reviewed(2, true); review.reviewed(0, true)
        assertFalse(review.ready("source-owner", digest))
        review.reviewed(1, true); assertTrue(review.ready("source-owner", digest))
        assertFalse(review.ready("different-owner", digest)); assertFalse(review.ready("source-owner", "b".repeat(64)))
        review.reviewed(1, false); assertFalse(review.ready("source-owner", digest))
    }
    @Test fun limitsRejectBeforeAllocatingUnboundedCoverage() {
        for (pages in listOf(-1, 0, 11, Int.MAX_VALUE)) {
            try { OfficeReview("owner", digest, pages); fail("Invalid page count") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun outOfRangePageCannotChangeCoverage() {
        val review = OfficeReview("owner", digest, 1)
        try { review.reviewed(1, true); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(review.ready("owner", digest)); assertFalse(review.contains(-1))
    }
    @Test fun diagnosticsContainNoReviewedIdentityOrContent() {
        assertEquals("OfficeReview(<redacted>)", OfficeReview("SYNTHETIC_PRIVATE_CANARY", digest, 1).toString())
    }
}
