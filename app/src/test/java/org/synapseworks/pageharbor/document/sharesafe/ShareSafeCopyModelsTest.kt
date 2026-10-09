package org.synapseworks.pageharbor.document.sharesafe

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.synapseworks.pageharbor.document.targetsize.TargetSizeBytes

class ShareSafeCopyModelsTest {
    @Test
    fun purposeValidationAcceptsBoundedUnicodeWithoutChangingMeaning() {
        listOf(
            "Pentru închiriere בלבד",
            "Nur für die Prüfung",
            "Pour l’inscription",
            "学校登録用",
            "केवल सत्यापन हेतु",
            "Copy for review ✅",
        ).forEach { text ->
            assertEquals(
                text,
                (ShareSafePurposeValidator.validate(text) as ShareSafePurposeValidation.Valid)
                    .normalized,
            )
        }
    }

    @Test
    fun purposeValidationRejectsEmptyLongMultilineAndControlInput() {
        assertEquals(
            ShareSafePurposeError.EMPTY,
            (ShareSafePurposeValidator.validate("  ") as ShareSafePurposeValidation.Invalid).reason,
        )
        assertEquals(
            ShareSafePurposeError.TOO_LONG,
            (ShareSafePurposeValidator.validate("x".repeat(121)) as ShareSafePurposeValidation.Invalid)
                .reason,
        )
        assertEquals(
            ShareSafePurposeError.TOO_MANY_LINES,
            (ShareSafePurposeValidator.validate("a\nb\nc\nd") as ShareSafePurposeValidation.Invalid)
                .reason,
        )
        assertEquals(
            ShareSafePurposeError.CONTROL_CHARACTER,
            (ShareSafePurposeValidator.validate("copy\u0000only") as ShareSafePurposeValidation.Invalid)
                .reason,
        )
    }

    @Test
    fun dateIsDisabledUntilUserExplicitlyEnablesSuggestion() {
        val initial = ShareSafeWatermarkConfig(enabled = true, purposeText = "For review only")
        assertFalse(initial.includeDate)
        assertEquals("For review only", initial.renderedText)

        val enabled = initial.withSuggestedLocalDate(
            today = LocalDate.of(2026, 10, 9),
            locale = Locale.UK,
        )
        assertTrue(enabled.includeDate)
        assertEquals("For review only\n9 Oct 2026", enabled.renderedText)
        assertEquals("Edited by user", enabled.copy(dateText = "Edited by user").dateText)
        assertFalse(enabled.copy(dateText = "x".repeat(41)).isValid)
        assertFalse(enabled.copy(dateText = "9 Oct\n2026").isValid)
    }

    @Test
    fun placementOpacityAndAllOutputSettingsBindWatermarkDigest() {
        val base = ShareSafeWatermarkConfig(enabled = true, purposeText = "For review only")
        assertEquals(3, ShareSafeWatermarkPlacement.entries.size)
        assertEquals(listOf(64, 96, 144), ShareSafeWatermarkOpacity.entries.map { it.alpha })
        assertNotEquals(base.digest(), base.copy(placement = ShareSafeWatermarkPlacement.FOOTER).digest())
        assertNotEquals(base.digest(), base.copy(opacity = ShareSafeWatermarkOpacity.STRONG).digest())
        assertNotEquals(base.digest(), base.copy(includeDate = true, dateText = "9 Oct 2026").digest())
    }

    @Test
    fun recipesAreConfigurationPresetsOverOneWorkflow() {
        val portal = ShareSafeRecipes.preset(ShareSafeRecipe.UPLOAD_TO_PORTAL)
        assertTrue(portal.targetSize.enabled)
        assertEquals(TargetSizeBytes.MB_1, portal.targetSize.maximumBytes)

        val email = ShareSafeRecipes.preset(ShareSafeRecipe.EMAIL_DOCUMENT)
        assertTrue(email.preferShare)
        assertEquals(TargetSizeBytes.MB_2, email.targetSize.maximumBytes)

        val archive = ShareSafeRecipes.preset(ShareSafeRecipe.ARCHIVE_DOCUMENT)
        assertFalse(archive.targetSize.enabled)
        assertTrue(archive.preserveSearchableText)
    }

    @Test
    fun securityPipelineOrderNeverCompressesBeforeSanitizationAndWatermarking() {
        assertEquals(
            listOf(
                ShareSafePipelineStage.SANITIZING,
                ShareSafePipelineStage.WATERMARKING,
                ShareSafePipelineStage.COMPRESSING,
                ShareSafePipelineStage.FINAL_VERIFYING,
            ),
            ShareSafePipelineOrder.required,
        )
    }
}
