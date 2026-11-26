package org.synapseworks.pageharbor.ui.ocr

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrScript
import org.synapseworks.pageharbor.ui.home.OcrResultScreen
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class OcrMultilingualSpellingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun latinLanguageSwitchUsesFrenchAndCopiesOnlyTheReviewedText() {
        verify(OcrScript.LATIN, "numérisaiton", "numérisation", "French")
    }

    @Test
    fun japaneseSpellingIsAvailableOnRecognizedText() {
        verify(OcrScript.JAPANESE, "申晴書", "申請書")
    }

    @Test
    fun chineseReviewUsesTraditionalSpellingWhenSelected() {
        verify(OcrScript.CHINESE, "末來", "未來", "Chinese (Traditional)")
    }

    @Test
    fun koreanSpellingIsAvailableOnRecognizedText() {
        verify(OcrScript.KOREAN, "문서뭉", "문서")
    }

    @Test
    fun devanagariSwitchUsesMarathi() {
        verify(OcrScript.DEVANAGARI, "प्रमाणपतर", "प्रमाणपत्र", "Marathi")
    }

    private fun verify(script: OcrScript, typo: String, expected: String, option: String? = null) {
        val snackbar = SnackbarHostState()
        val copies = mutableListOf<String>()
        compose.setContent {
            PageHarborTheme {
                OcrResultScreen(
                    result = OcrResult(listOf(OcrPageResult(0, typo, actualScript = script))),
                    pageUris = emptyList(),
                    pageMetadata = emptyList(),
                    selectedPageIndex = 0,
                    onSelectedPageChange = {},
                    snackbarHostState = snackbar,
                    onBack = {},
                    onRecognizeAgain = {},
                    onClearRecognizedText = {},
                    onCopyText = { copies += it },
                )
            }
        }
        compose.onNodeWithText("Review spelling").performScrollTo().performClick()
        if (option != null) {
            compose.onNodeWithText("Change spelling language").performClick()
            compose.onNodeWithText(option).performClick()
            compose.onNodeWithText("$option spelling").assertIsDisplayed()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Use $expected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Copy reviewed page").assertIsNotEnabled()
        compose.onNodeWithText("Use $expected").performClick()
        compose.onNodeWithText("Copy reviewed page").performClick()
        compose.runOnIdle { assertEquals(listOf(expected), copies) }
        compose.onNodeWithText(typo).assertIsDisplayed()
    }
}
