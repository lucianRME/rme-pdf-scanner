package org.synapseworks.pageharbor.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalFilenameComparatorTest {
    @Test
    fun ordersNumericPageNamesByMagnitude() {
        val names = listOf("page-10.jpg", "page-2.jpg", "page-01.jpg", "page-1.jpg")

        assertEquals(
            listOf("page-1.jpg", "page-01.jpg", "page-2.jpg", "page-10.jpg"),
            names.sortedWith(NaturalFilenameComparator),
        )
    }

    @Test
    fun orderingIsDeterministicWhenCaseFoldsEqual() {
        assertEquals(
            listOf("Page 1.JPG", "page 1.jpg"),
            listOf("page 1.jpg", "Page 1.JPG").sortedWith(NaturalFilenameComparator),
        )
    }

    @Test
    fun arbitrarilyLongDigitRunsDoNotOverflow() {
        val smaller = "page-${"9".repeat(80)}.jpg"
        val larger = "page-1${"0".repeat(80)}.jpg"

        assertEquals(listOf(smaller, larger), listOf(larger, smaller).sortedWith(NaturalFilenameComparator))
    }
}
