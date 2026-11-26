package org.synapseworks.pageharbor.ui.protectedpdf

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfExportState

class PasswordPdfExportDialogTest {
    @get:Rule val compose = createComposeRule()
    private val source = File("synthetic-source.pdf")
    // Never let a Compose failure dump an editable password or an accessibility tree to diagnostics.
    private fun safeUi(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull { it.className.startsWith("org.synapseworks") }?.lineNumber
            throw AssertionError("M05 password UI requirement failed: ${failure.javaClass.simpleName}, line $line")
        }
    }

    @Test fun confirmationPolicyErrorsAreAccessibleAndPrepareReceivesOnlyValidatedInput() = safeUi {
        var prepared = 0
        compose.setContent { MaterialTheme { PasswordPdfExportDialog(
            state = PasswordPdfExportState(visible = true, source = source, sourcePages = 2),
            onDismiss = {}, onOptionsChanged = {}, onPrepare = { chars ->
                try { assertTrue(chars.contentEquals("Parolă-șț-123".toCharArray())); prepared++ }
                finally { chars.fill('\u0000') }
            }, onSave = {}, onShare = {},
        ) } }
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("protect-password").performScrollTo().performTextInput("short")
        compose.onNodeWithTag("protect-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("protect-password").performTextReplacement("Parolă-șț-123")
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextInput("different-input")
        compose.onNodeWithTag("protect-confirmation").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextReplacement("Parolă-șț-123")
        compose.onNodeWithTag("protect-confirmation").performImeAction()
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
        compose.onNodeWithTag("protect-password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("protect-confirmation").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun plaintextFieldsDoNotSurviveSavedStateRestoration() = safeUi {
        val restorer = StateRestorationTester(compose)
        restorer.setContent { MaterialTheme { PasswordPdfExportDialog(
            state = PasswordPdfExportState(visible = true, source = source, sourcePages = 1),
            onDismiss = {}, onOptionsChanged = {}, onPrepare = { it.fill('\u0000') }, onSave = {}, onShare = {},
        ) } }
        compose.onNodeWithTag("protect-password").performScrollTo().performTextInput("synthetic-field-value")
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextInput("synthetic-field-value")
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("protect-password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("protect-confirmation").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsNotEnabled()
    }

    @Test fun changingAFieldInvalidatesThePreparedCopyAndFutureRoutesAreAbsent() = safeUi {
        val state = mutableStateOf(PasswordPdfExportState(
            visible = true, source = source, sourcePages = 2, output = File("synthetic-output.pdf"),
        ))
        var invalidations = 0
        compose.setContent { MaterialTheme { PasswordPdfExportDialog(
            state = state.value, onDismiss = {},
            onOptionsChanged = { invalidations++; state.value = state.value.copy(output = null) },
            onPrepare = { it.fill('\u0000') }, onSave = {}, onShare = {},
        ) } }
        compose.onNodeWithText("Save encrypted PDF").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("protect-password").performScrollTo().performTextInput("new-value")
        compose.onNodeWithText("Save encrypted PDF").assertDoesNotExist()
        listOf("Signature", "Book pages", "Two pages per sheet", "Preview").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Close").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(invalidations > 0) }
    }
    @Test fun oversizedConfirmationIsAccessibleAndCanBeCorrectedWithoutChangingPassword() = safeUi {
        compose.setContent { MaterialTheme { PasswordPdfExportDialog(
            state = PasswordPdfExportState(visible = true, source = source, sourcePages = 1),
            onDismiss = {}, onOptionsChanged = {}, onPrepare = { it.fill('\u0000') }, onSave = {}, onShare = {},
        ) } }
        compose.onNodeWithTag("protect-password").performScrollTo().performTextInput("synthetic-valid-value")
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextInput("x".repeat(513))
        compose.onNodeWithTag("protect-confirmation").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("protect-confirmation").performImeAction()
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("protect-confirmation").performScrollTo().performTextReplacement("synthetic-valid-value")
        compose.onNodeWithTag("protect-confirmation").performImeAction()
        compose.onNodeWithTag("protect-prepare").performScrollTo().assertIsEnabled()
    }

}
