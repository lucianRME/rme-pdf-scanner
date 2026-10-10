package org.synapseworks.pageharbor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RmeGraphiteTealThemeTest {
    @Test
    fun materialSchemesUseTheApprovedGraphiteTealMappings() {
        assertScheme(
            DarkColorScheme,
            primary = 0xFF42D6C3,
            onPrimary = 0xFF00201B,
            primaryContainer = 0xFF0D403A,
            onPrimaryContainer = 0xFF9EF4E7,
            secondary = 0xFF7BCBBE,
            tertiary = 0xFF78C7E8,
            background = 0xFF080B0D,
            onBackground = 0xFFEAF2F1,
            surface = 0xFF0D1214,
            onSurface = 0xFFEAF2F1,
            surfaceVariant = 0xFF202B2E,
            onSurfaceVariant = 0xFFB7C3C1,
            outline = 0xFF607174,
            outlineVariant = 0xFF2B3A3D,
            inversePrimary = 0xFF006B5F,
        )
        assertScheme(
            LightColorScheme,
            primary = 0xFF006B5F,
            onPrimary = 0xFFFFFFFF,
            primaryContainer = 0xFFC5F2EA,
            onPrimaryContainer = 0xFF003C35,
            secondary = 0xFF3C6B63,
            tertiary = 0xFF146B82,
            background = 0xFFF6F8F7,
            onBackground = 0xFF17211F,
            surface = 0xFFFFFFFF,
            onSurface = 0xFF17211F,
            surfaceVariant = 0xFFEAF0EE,
            onSurfaceVariant = 0xFF4E5C59,
            outline = 0xFF6D7C79,
            outlineVariant = 0xFFD3DDDA,
            inversePrimary = 0xFF42D6C3,
        )
    }

    @Test
    fun extendedColorsCoverApprovedSemanticAndComponentStates() {
        assertEquals(0xFF31BBAA.toInt(), DarkRmeExtendedColors.primaryPressed.toArgb())
        assertEquals(0xFF1B2224.toInt(), DarkRmeExtendedColors.disabledBackground.toArgb())
        assertEquals(0xFF687472.toInt(), DarkRmeExtendedColors.textDisabled.toArgb())
        assertEquals(0xFF5FD39A.toInt(), DarkRmeExtendedColors.success.toArgb())
        assertEquals(0xFFF2C66D.toInt(), DarkRmeExtendedColors.warning.toArgb())
        assertEquals(0xFF050607.toInt(), DarkRmeExtendedColors.previewBackground.toArgb())
        assertEquals(DarkColorScheme.primary.toArgb(), DarkRmeExtendedColors.fabBackground.toArgb())

        assertEquals(0xFF00584F.toInt(), LightRmeExtendedColors.primaryPressed.toArgb())
        assertEquals(0xFFE7ECEA.toInt(), LightRmeExtendedColors.disabledBackground.toArgb())
        assertEquals(0xFF89928F.toInt(), LightRmeExtendedColors.textDisabled.toArgb())
        assertEquals(0xFF1D7A4D.toInt(), LightRmeExtendedColors.success.toArgb())
        assertEquals(0xFF8A5A00.toInt(), LightRmeExtendedColors.warning.toArgb())
        assertEquals(0xFFE5E9E7.toInt(), LightRmeExtendedColors.previewBackground.toArgb())
        assertEquals(LightColorScheme.primary.toArgb(), LightRmeExtendedColors.fabBackground.toArgb())
    }

    @Test
    fun approvedNormalTextAndPrimaryPairsMeetContrastTarget() {
        listOf(
            DarkColorScheme.onBackground to DarkColorScheme.background,
            DarkColorScheme.onSurfaceVariant to DarkColorScheme.background,
            DarkColorScheme.primary to DarkColorScheme.background,
            DarkColorScheme.onPrimary to DarkColorScheme.primary,
            LightColorScheme.onBackground to LightColorScheme.background,
            LightColorScheme.onSurfaceVariant to LightColorScheme.background,
            LightColorScheme.primary to LightColorScheme.background,
            LightColorScheme.onPrimary to LightColorScheme.primary,
        ).forEach { (foreground, background) ->
            assertTrue(
                "Expected contrast >= 4.5, was ${contrastRatio(foreground, background)}",
                contrastRatio(foreground, background) >= 4.5,
            )
        }
    }

    @Test
    fun productTokenModelsContainNoFormerMaterialOrRmePurple() {
        val forbidden = setOf(
            0xFF6750A4.toInt(),
            0xFFD0BCFF.toInt(),
            0xFFCABAF2.toInt(),
            0xFF302746.toInt(),
            0xFF3D3556.toInt(),
            0xFFE9E2FF.toInt(),
            0xFF66558A.toInt(),
            0xFFECE4FF.toInt(),
            0xFF33284A.toInt(),
        )
        val productColors = schemeColors(DarkColorScheme) + schemeColors(LightColorScheme) +
            extendedColors(DarkRmeExtendedColors) + extendedColors(LightRmeExtendedColors)
        assertTrue(productColors.map(Color::toArgb).none(forbidden::contains))
    }

    private fun assertScheme(
        scheme: ColorScheme,
        primary: Long,
        onPrimary: Long,
        primaryContainer: Long,
        onPrimaryContainer: Long,
        secondary: Long,
        tertiary: Long,
        background: Long,
        onBackground: Long,
        surface: Long,
        onSurface: Long,
        surfaceVariant: Long,
        onSurfaceVariant: Long,
        outline: Long,
        outlineVariant: Long,
        inversePrimary: Long,
    ) {
        assertEquals(primary.toInt(), scheme.primary.toArgb())
        assertEquals(onPrimary.toInt(), scheme.onPrimary.toArgb())
        assertEquals(primaryContainer.toInt(), scheme.primaryContainer.toArgb())
        assertEquals(onPrimaryContainer.toInt(), scheme.onPrimaryContainer.toArgb())
        assertEquals(secondary.toInt(), scheme.secondary.toArgb())
        assertEquals(tertiary.toInt(), scheme.tertiary.toArgb())
        assertEquals(background.toInt(), scheme.background.toArgb())
        assertEquals(onBackground.toInt(), scheme.onBackground.toArgb())
        assertEquals(surface.toInt(), scheme.surface.toArgb())
        assertEquals(onSurface.toInt(), scheme.onSurface.toArgb())
        assertEquals(surfaceVariant.toInt(), scheme.surfaceVariant.toArgb())
        assertEquals(onSurfaceVariant.toInt(), scheme.onSurfaceVariant.toArgb())
        assertEquals(outline.toInt(), scheme.outline.toArgb())
        assertEquals(outlineVariant.toInt(), scheme.outlineVariant.toArgb())
        assertEquals(inversePrimary.toInt(), scheme.inversePrimary.toArgb())
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val component = value.toDouble()
            return if (component <= 0.04045) component / 12.92
            else Math.pow((component + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun schemeColors(scheme: ColorScheme) = listOf(
        scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
        scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer,
        scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer,
        scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface,
        scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline, scheme.outlineVariant,
        scheme.inversePrimary, scheme.inverseSurface, scheme.inverseOnSurface,
        scheme.primaryFixed, scheme.primaryFixedDim, scheme.onPrimaryFixed, scheme.onPrimaryFixedVariant,
        scheme.secondaryFixed, scheme.secondaryFixedDim, scheme.onSecondaryFixed, scheme.onSecondaryFixedVariant,
        scheme.tertiaryFixed, scheme.tertiaryFixedDim, scheme.onTertiaryFixed, scheme.onTertiaryFixedVariant,
    )

    private fun extendedColors(colors: RmeExtendedColors) = listOf(
        colors.primaryPressed, colors.surfaceLow, colors.surfaceMedium, colors.surfaceHigh,
        colors.divider, colors.outlineSubtle, colors.textTertiary, colors.textDisabled,
        colors.iconPrimary, colors.iconSecondary, colors.iconDisabled, colors.disabledBackground,
        colors.selectedBackground, colors.selectedForeground, colors.success, colors.warning,
        colors.info, colors.previewBackground, colors.pageSurface, colors.redactionFill,
        colors.navigationBackground, colors.navigationActive, colors.navigationInactive,
        colors.navigationIndicator, colors.fabBackground, colors.fabForeground, colors.sheetBackground,
    )
}
