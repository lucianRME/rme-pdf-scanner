package org.synapseworks.pageharbor.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHarborDesignTokensTest {
    @Test
    fun spacingScaleIsOrderedAndCoversCurrentScreenRhythm() {
        assertTrue(PageHarborSpacing.extraSmall < PageHarborSpacing.compact)
        assertTrue(PageHarborSpacing.compact < PageHarborSpacing.small)
        assertTrue(PageHarborSpacing.small < PageHarborSpacing.dialog)
        assertTrue(PageHarborSpacing.dialog < PageHarborSpacing.medium)
        assertTrue(PageHarborSpacing.small < PageHarborSpacing.medium)
        assertTrue(PageHarborSpacing.medium < PageHarborSpacing.large)
        assertTrue(PageHarborSpacing.large < PageHarborSpacing.extraLarge)
        assertTrue(PageHarborSpacing.extraLarge < PageHarborSpacing.screen)
        assertTrue(PageHarborSpacing.screen < PageHarborSpacing.prominentAction)
    }

    @Test
    fun layoutTokensProtectPhoneMarginsAndMaterialTouchTargets() {
        assertEquals(16.dp, PageHarborLayout.compactScreenHorizontalPadding)
        assertEquals(24.dp, PageHarborLayout.mediumScreenHorizontalPadding)
        assertEquals(32.dp, PageHarborLayout.expandedScreenHorizontalPadding)
        assertEquals(48.dp, PageHarborLayout.minimumTouchTarget)
        assertEquals(20.dp, PageHarborLayout.inlineProgressIndicatorSize)
        assertTrue(
            PageHarborLayout.documentPreviewMinHeight < PageHarborLayout.documentPreviewMaxHeight,
        )
        assertTrue(PageHarborLayout.homeCenteredContentMinHeight > 0.dp)
        assertTrue(PageHarborLayout.expandedContentMaxWidth > PageHarborLayout.homeContentMaxWidth)
        assertTrue(PageHarborLayout.libraryContentMaxWidth > PageHarborLayout.expandedContentMaxWidth)
    }
}
