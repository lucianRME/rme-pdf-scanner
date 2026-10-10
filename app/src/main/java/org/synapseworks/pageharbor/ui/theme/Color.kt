package org.synapseworks.pageharbor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// RME Graphite Teal: deterministic product colors, independent of device dynamic color.
internal val RmeDarkBackground = Color(0xFF080B0D)
internal val RmeDarkSurface = Color(0xFF0D1214)
internal val RmeDarkSurfaceLow = Color(0xFF101618)
internal val RmeDarkSurfaceMedium = Color(0xFF151D1F)
internal val RmeDarkSurfaceHigh = Color(0xFF1B2527)
internal val RmeDarkSurfaceContainer = Color(0xFF141B1D)
internal val RmeDarkSurfaceContainerHigh = Color(0xFF192224)
internal val RmeDarkSurfaceContainerHighest = Color(0xFF202B2E)
internal val RmeDarkDivider = Color(0xFF2B3A3D)
internal val RmeDarkOutline = Color(0xFF607174)
internal val RmeDarkOutlineSubtle = Color(0xFF253235)
internal val RmeDarkPrimary = Color(0xFF42D6C3)
internal val RmeDarkPrimaryPressed = Color(0xFF31BBAA)
internal val RmeDarkOnPrimary = Color(0xFF00201B)
internal val RmeDarkPrimaryContainer = Color(0xFF0D403A)
internal val RmeDarkOnPrimaryContainer = Color(0xFF9EF4E7)
internal val RmeDarkSecondary = Color(0xFF7BCBBE)
internal val RmeDarkOnSecondary = Color(0xFF003731)
internal val RmeDarkSecondaryContainer = Color(0xFF193B37)
internal val RmeDarkOnSecondaryContainer = Color(0xFFB4EEE5)
internal val RmeDarkTertiary = Color(0xFF78C7E8)
internal val RmeDarkOnTertiary = Color(0xFF002F3D)
internal val RmeDarkTertiaryContainer = Color(0xFF173E4C)
internal val RmeDarkOnTertiaryContainer = Color(0xFFB8E9FA)
internal val RmeDarkTextPrimary = Color(0xFFEAF2F1)
internal val RmeDarkTextSecondary = Color(0xFFB7C3C1)
internal val RmeDarkTextTertiary = Color(0xFF84928F)
internal val RmeDarkTextDisabled = Color(0xFF687472)
internal val RmeDarkIconSecondary = Color(0xFFA4B2AF)
internal val RmeDarkDisabledBackground = Color(0xFF1B2224)
internal val RmeDarkSuccess = Color(0xFF5FD39A)
internal val RmeDarkWarning = Color(0xFFF2C66D)
internal val RmeDarkError = Color(0xFFFFB4AB)

internal val RmeLightBackground = Color(0xFFF6F8F7)
internal val RmeLightSurface = Color(0xFFFFFFFF)
internal val RmeLightSurfaceLow = Color(0xFFF3F6F5)
internal val RmeLightSurfaceMedium = Color(0xFFEAF0EE)
internal val RmeLightSurfaceHigh = Color(0xFFE4ECE9)
internal val RmeLightSurfaceContainer = Color(0xFFEEF3F1)
internal val RmeLightSurfaceContainerHigh = Color(0xFFE4ECE9)
internal val RmeLightSurfaceContainerHighest = Color(0xFFDCE6E3)
internal val RmeLightDivider = Color(0xFFD3DDDA)
internal val RmeLightOutline = Color(0xFF6D7C79)
internal val RmeLightOutlineSubtle = Color(0xFFCDD8D5)
internal val RmeLightPrimary = Color(0xFF006B5F)
internal val RmeLightPrimaryPressed = Color(0xFF00584F)
internal val RmeLightOnPrimary = Color(0xFFFFFFFF)
internal val RmeLightPrimaryContainer = Color(0xFFC5F2EA)
internal val RmeLightOnPrimaryContainer = Color(0xFF003C35)
internal val RmeLightSecondary = Color(0xFF3C6B63)
internal val RmeLightOnSecondary = Color(0xFFFFFFFF)
internal val RmeLightSecondaryContainer = Color(0xFFD7EAE5)
internal val RmeLightOnSecondaryContainer = Color(0xFF173E38)
internal val RmeLightTertiary = Color(0xFF146B82)
internal val RmeLightOnTertiary = Color(0xFFFFFFFF)
internal val RmeLightTertiaryContainer = Color(0xFFC5ECF8)
internal val RmeLightOnTertiaryContainer = Color(0xFF0A3D4A)
internal val RmeLightTextPrimary = Color(0xFF17211F)
internal val RmeLightTextSecondary = Color(0xFF4E5C59)
internal val RmeLightTextTertiary = Color(0xFF63716E)
internal val RmeLightTextDisabled = Color(0xFF89928F)
internal val RmeLightIconSecondary = Color(0xFF566460)
internal val RmeLightDisabledBackground = Color(0xFFE7ECEA)
internal val RmeLightSuccess = Color(0xFF1D7A4D)
internal val RmeLightWarning = Color(0xFF8A5A00)
internal val RmeLightError = Color(0xFFB3261E)

@Immutable
data class RmeExtendedColors(
    val primaryPressed: Color,
    val surfaceLow: Color,
    val surfaceMedium: Color,
    val surfaceHigh: Color,
    val divider: Color,
    val outlineSubtle: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconDisabled: Color,
    val disabledBackground: Color,
    val selectedBackground: Color,
    val selectedForeground: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val previewBackground: Color,
    val pageSurface: Color,
    val redactionFill: Color,
    val navigationBackground: Color,
    val navigationActive: Color,
    val navigationInactive: Color,
    val navigationIndicator: Color,
    val fabBackground: Color,
    val fabForeground: Color,
    val sheetBackground: Color,
)

internal val DarkRmeExtendedColors = RmeExtendedColors(
    primaryPressed = RmeDarkPrimaryPressed,
    surfaceLow = RmeDarkSurfaceLow,
    surfaceMedium = RmeDarkSurfaceMedium,
    surfaceHigh = RmeDarkSurfaceHigh,
    divider = RmeDarkDivider,
    outlineSubtle = RmeDarkOutlineSubtle,
    textTertiary = RmeDarkTextTertiary,
    textDisabled = RmeDarkTextDisabled,
    iconPrimary = RmeDarkTextPrimary,
    iconSecondary = RmeDarkIconSecondary,
    iconDisabled = RmeDarkTextDisabled,
    disabledBackground = RmeDarkDisabledBackground,
    selectedBackground = RmeDarkPrimaryContainer,
    selectedForeground = RmeDarkOnPrimaryContainer,
    success = RmeDarkSuccess,
    warning = RmeDarkWarning,
    info = RmeDarkTertiary,
    previewBackground = Color(0xFF050607),
    pageSurface = Color.White,
    redactionFill = Color.Black,
    navigationBackground = Color(0xFF0E1315),
    navigationActive = RmeDarkPrimary,
    navigationInactive = Color(0xFF879592),
    navigationIndicator = Color(0xFF173936),
    fabBackground = RmeDarkPrimary,
    fabForeground = RmeDarkOnPrimary,
    sheetBackground = RmeDarkSurfaceContainer,
)

internal val LightRmeExtendedColors = RmeExtendedColors(
    primaryPressed = RmeLightPrimaryPressed,
    surfaceLow = RmeLightSurfaceLow,
    surfaceMedium = RmeLightSurfaceMedium,
    surfaceHigh = RmeLightSurfaceHigh,
    divider = RmeLightDivider,
    outlineSubtle = RmeLightOutlineSubtle,
    textTertiary = RmeLightTextTertiary,
    textDisabled = RmeLightTextDisabled,
    iconPrimary = RmeLightTextPrimary,
    iconSecondary = RmeLightIconSecondary,
    iconDisabled = RmeLightTextDisabled,
    disabledBackground = RmeLightDisabledBackground,
    selectedBackground = RmeLightPrimaryContainer,
    selectedForeground = RmeLightOnPrimaryContainer,
    success = RmeLightSuccess,
    warning = RmeLightWarning,
    info = RmeLightTertiary,
    previewBackground = Color(0xFFE5E9E7),
    pageSurface = Color.White,
    redactionFill = Color.Black,
    navigationBackground = Color.White,
    navigationActive = RmeLightPrimary,
    navigationInactive = Color(0xFF66736F),
    navigationIndicator = Color(0xFFD5F4EE),
    fabBackground = RmeLightPrimary,
    fabForeground = Color.White,
    sheetBackground = Color.White,
)
