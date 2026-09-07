package org.synapseworks.pageharbor.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared layout measurements for RME PDF Scanner screens. They deliberately preserve the current
 * visual density while giving later UI work one semantic source of truth.
 */
object PageHarborSpacing {
    val extraSmall = 4.dp
    val compact = 6.dp
    val small = 8.dp
    val dialog = 10.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 24.dp
    val screen = 32.dp
    val prominentAction = 40.dp
}

object PageHarborLayout {
    val compactScreenHorizontalPadding = 24.dp
    val compactScreenVerticalPadding = 16.dp
    val homeContentMaxWidth = 520.dp
    val homeCenteredContentMinHeight = 640.dp
    val expandedContentMaxWidth = 720.dp
    val documentPreviewMinHeight = 180.dp
    val documentPreviewMaxHeight = 220.dp
    val editorDocumentPreviewMinHeight = 280.dp
    val editorDocumentPreviewMaxHeight = 440.dp
    val scrollableDialogContentMaxHeight = 360.dp
    val inlineProgressIndicatorSize = 20.dp
    val minimumTouchTarget = 48.dp
}
