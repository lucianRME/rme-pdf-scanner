package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorPaletteLightSurfaceVariant = Color(0xFFE6ECF5)
private val ColorPaletteLightOnSurfaceVariant = Color(0xFF445064)
private val ColorPaletteLightOutline = Color(0xFF6F7A8C)
private val ColorPaletteLightOutlineVariant = Color(0xFFC4CCD8)
private val ColorPaletteDarkSurfaceVariant = Color(0xFF273447)
private val ColorPaletteDarkOnSurfaceVariant = Color(0xFFC6D0E0)
private val ColorPaletteDarkOutline = Color(0xFF8B96A8)
private val ColorPaletteDarkOutlineVariant = Color(0xFF3C485A)

private val DarkColorScheme = darkColorScheme(
    primary = PageHarborAccentBlue,
    onPrimary = PageHarborDarkSlate,
    primaryContainer = PageHarborPrimaryDark,
    onPrimaryContainer = PageHarborWhite,
    secondary = Color(0xFFCABAF2),
    onSecondary = Color(0xFF302746),
    secondaryContainer = Color(0xFF3D3556),
    onSecondaryContainer = Color(0xFFE9E2FF),
    background = PageHarborDarkBackground,
    onBackground = PageHarborDarkOnSurface,
    surface = PageHarborDarkSurface,
    onSurface = PageHarborDarkOnSurface,
    surfaceVariant = ColorPaletteDarkSurfaceVariant,
    onSurfaceVariant = ColorPaletteDarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF080E19),
    surfaceContainerLow = Color(0xFF151D2B),
    surfaceContainer = Color(0xFF192333),
    surfaceContainerHigh = Color(0xFF202B3C),
    surfaceContainerHighest = ColorPaletteDarkSurfaceVariant,
    outline = ColorPaletteDarkOutline,
    outlineVariant = ColorPaletteDarkOutlineVariant,
    error = PageHarborDarkError,
    onError = PageHarborDarkSlate,
)

private val LightColorScheme = lightColorScheme(
    primary = PageHarborPrimaryBlue,
    onPrimary = PageHarborWhite,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF0B3A79),
    secondary = Color(0xFF66558A),
    onSecondary = PageHarborWhite,
    secondaryContainer = Color(0xFFECE4FF),
    onSecondaryContainer = Color(0xFF33284A),
    background = PageHarborLightSurface,
    onBackground = PageHarborDarkSlate,
    surface = PageHarborWhite,
    onSurface = PageHarborDarkSlate,
    surfaceVariant = ColorPaletteLightSurfaceVariant,
    onSurfaceVariant = ColorPaletteLightOnSurfaceVariant,
    surfaceContainerLowest = PageHarborWhite,
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFEAF0F7),
    surfaceContainerHighest = Color(0xFFE1E8F2),
    outline = ColorPaletteLightOutline,
    outlineVariant = ColorPaletteLightOutlineVariant,
    error = PageHarborError,
    onError = PageHarborWhite,
)

@Composable
fun PageHarborTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PageHarborTypography,
        shapes = PageHarborShapes,
        content = content,
    )
}
