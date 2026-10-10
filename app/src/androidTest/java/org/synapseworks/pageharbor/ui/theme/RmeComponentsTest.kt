package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RmeComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tonalChoiceExposesSelectionAndUpdatesWithoutAnOutlinedControl() {
        val selected = mutableStateOf("Normal")
        composeTestRule.setContent {
            PageHarborTheme(darkTheme = true) {
                Row {
                    listOf("Light", "Normal").forEach { label ->
                        RmeTonalChoice(
                            selected = selected.value == label,
                            onClick = { selected.value = label },
                        ) { Text(label) }
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Light").assertIsNotSelected().performClick()
        composeTestRule.onNodeWithText("Light").assertIsSelected()
        composeTestRule.onNodeWithText("Normal").assertIsNotSelected()
        assertEquals("Light", selected.value)
    }

    @Test
    fun filledSearchKeepsQuerySemanticsAndExistingClearAction() {
        val query = mutableStateOf("")
        composeTestRule.setContent {
            PageHarborTheme(darkTheme = true) {
                RmeSearchField(
                    value = query.value,
                    onValueChange = { query.value = it },
                    placeholder = "Search documents",
                    clearContentDescription = "Clear search",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("invoice")
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("invoice")
        composeTestRule.onNodeWithContentDescription("Clear search").performClick()
        composeTestRule.onNodeWithText("Search documents").assertExists()
        assertEquals("", query.value)
    }

    @Test
    fun filledTextFieldPreservesInputAndErrorSupportingSemantics() {
        val value = mutableStateOf("")
        composeTestRule.setContent {
            PageHarborTheme(darkTheme = false) {
                RmeFilledTextField(
                    value = value.value,
                    onValueChange = { value.value = it },
                    label = { Text("Purpose") },
                    supportingText = { Text("Purpose is required") },
                    isError = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Account verification")
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("Account verification")
        composeTestRule.onNodeWithText("Purpose is required").assertExists()
        assertEquals("Account verification", value.value)
    }
}
