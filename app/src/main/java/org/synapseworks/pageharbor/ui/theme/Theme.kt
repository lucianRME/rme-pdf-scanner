package org.synapseworks.pageharbor.ui.theme

import org.synapseworks.pageharbor.ui.SensitiveDocumentClipboard

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val DarkColorScheme = darkColorScheme(
    primary = RmeDarkPrimary,
    onPrimary = RmeDarkOnPrimary,
    primaryContainer = RmeDarkPrimaryContainer,
    onPrimaryContainer = RmeDarkOnPrimaryContainer,
    inversePrimary = RmeLightPrimary,
    secondary = RmeDarkSecondary,
    onSecondary = RmeDarkOnSecondary,
    secondaryContainer = RmeDarkSecondaryContainer,
    onSecondaryContainer = RmeDarkOnSecondaryContainer,
    tertiary = RmeDarkTertiary,
    onTertiary = RmeDarkOnTertiary,
    tertiaryContainer = RmeDarkTertiaryContainer,
    onTertiaryContainer = RmeDarkOnTertiaryContainer,
    background = RmeDarkBackground,
    onBackground = RmeDarkTextPrimary,
    surface = RmeDarkSurface,
    onSurface = RmeDarkTextPrimary,
    surfaceVariant = RmeDarkSurfaceContainerHighest,
    onSurfaceVariant = RmeDarkTextSecondary,
    surfaceTint = RmeDarkPrimary,
    inverseSurface = RmeDarkTextPrimary,
    inverseOnSurface = RmeLightTextPrimary,
    surfaceContainerLowest = RmeDarkBackground,
    surfaceContainerLow = RmeDarkSurfaceLow,
    surfaceContainer = RmeDarkSurfaceContainer,
    surfaceContainerHigh = RmeDarkSurfaceContainerHigh,
    surfaceContainerHighest = RmeDarkSurfaceContainerHighest,
    outline = RmeDarkOutline,
    outlineVariant = RmeDarkDivider,
    error = RmeDarkError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
    primaryFixed = Color(0xFFC5F2EA),
    primaryFixedDim = RmeDarkPrimary,
    onPrimaryFixed = RmeDarkOnPrimary,
    onPrimaryFixedVariant = RmeLightOnPrimaryContainer,
    secondaryFixed = Color(0xFFD7EAE5),
    secondaryFixedDim = RmeDarkSecondary,
    onSecondaryFixed = RmeDarkOnSecondary,
    onSecondaryFixedVariant = RmeLightOnSecondaryContainer,
    tertiaryFixed = Color(0xFFC5ECF8),
    tertiaryFixedDim = RmeDarkTertiary,
    onTertiaryFixed = RmeDarkOnTertiary,
    onTertiaryFixedVariant = RmeLightOnTertiaryContainer,
)

internal val LightColorScheme = lightColorScheme(
    primary = RmeLightPrimary,
    onPrimary = RmeLightOnPrimary,
    primaryContainer = RmeLightPrimaryContainer,
    onPrimaryContainer = RmeLightOnPrimaryContainer,
    inversePrimary = RmeDarkPrimary,
    secondary = RmeLightSecondary,
    onSecondary = RmeLightOnSecondary,
    secondaryContainer = RmeLightSecondaryContainer,
    onSecondaryContainer = RmeLightOnSecondaryContainer,
    tertiary = RmeLightTertiary,
    onTertiary = RmeLightOnTertiary,
    tertiaryContainer = RmeLightTertiaryContainer,
    onTertiaryContainer = RmeLightOnTertiaryContainer,
    background = RmeLightBackground,
    onBackground = RmeLightTextPrimary,
    surface = RmeLightSurface,
    onSurface = RmeLightTextPrimary,
    surfaceVariant = RmeLightSurfaceMedium,
    onSurfaceVariant = RmeLightTextSecondary,
    surfaceTint = RmeLightPrimary,
    inverseSurface = RmeLightTextPrimary,
    inverseOnSurface = RmeLightBackground,
    surfaceContainerLowest = RmeLightSurface,
    surfaceContainerLow = RmeLightSurfaceLow,
    surfaceContainer = RmeLightSurfaceContainer,
    surfaceContainerHigh = RmeLightSurfaceContainerHigh,
    surfaceContainerHighest = RmeLightSurfaceContainerHighest,
    outline = RmeLightOutline,
    outlineVariant = RmeLightDivider,
    error = RmeLightError,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color.Black,
    primaryFixed = RmeLightPrimaryContainer,
    primaryFixedDim = RmeDarkPrimary,
    onPrimaryFixed = RmeDarkOnPrimary,
    onPrimaryFixedVariant = RmeLightOnPrimaryContainer,
    secondaryFixed = RmeLightSecondaryContainer,
    secondaryFixedDim = RmeDarkSecondary,
    onSecondaryFixed = RmeDarkOnSecondary,
    onSecondaryFixedVariant = RmeLightOnSecondaryContainer,
    tertiaryFixed = RmeLightTertiaryContainer,
    tertiaryFixedDim = RmeDarkTertiary,
    onTertiaryFixed = RmeDarkOnTertiary,
    onTertiaryFixedVariant = RmeLightOnTertiaryContainer,
)

private val LocalRmeExtendedColors = staticCompositionLocalOf { LightRmeExtendedColors }

val MaterialTheme.rmeColors: RmeExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalRmeExtendedColors.current

@Composable
fun PageHarborTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkRmeExtendedColors else LightRmeExtendedColors

    CompositionLocalProvider(LocalRmeExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PageHarborTypography,
            shapes = PageHarborShapes,
            content = { SensitiveDocumentClipboard(content) },
        )
    }
}

internal fun ComponentActivity.setPageHarborContent(content: @Composable () -> Unit) {
    setContent {
        PageHarborTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}
