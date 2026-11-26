package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
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
                RmeTextField(
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

    @Test
    fun largeTextActionsWrapInOrderAndKeepTheirTouchTargets() {
        var clicks = 0
        composeTestRule.setContent {
            PageHarborTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Column(Modifier.width(160.dp)) {
                        RmeActionRow {
                            RmePrimaryButton({ clicks++ }, Modifier.testTag("save")) { Text("Save a copy") }
                            RmeSecondaryButton({ clicks++ }, Modifier.testTag("share")) { Text("Share a copy") }
                        }
                        RmeIconButton({ clicks++ }, Modifier.testTag("icon"), enabled = false) { Text("+") }
                    }
                }
            }
        }
        val save = composeTestRule.onNodeWithTag("save")
        val share = composeTestRule.onNodeWithTag("share")
        save.assertHeightIsAtLeast(48.dp).performClick()
        share.assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(share.getUnclippedBoundsInRoot().top >= save.getUnclippedBoundsInRoot().bottom)
        composeTestRule.onNodeWithTag("icon").assertIsNotEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertEquals(2, clicks)
    }

    @Test
    fun equalWidthActionsHaveAVisibleGapBetweenTouchTargets() {
        composeTestRule.setContent {
            PageHarborTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                    Column(Modifier.width(280.dp)) {
                        RmeActionRow {
                            RmeSecondaryButton({}, Modifier.weight(1f).testTag("insert")) { Text("Insert") }
                            RmeSecondaryButton({}, Modifier.weight(1f).testTag("delete")) { Text("Delete") }
                        }
                    }
                }
            }
        }
        val first = composeTestRule.onNodeWithTag("insert").getUnclippedBoundsInRoot()
        val second = composeTestRule.onNodeWithTag("delete").getUnclippedBoundsInRoot()
        assertEquals(first.top.value, second.top.value, 0.5f)
        assertTrue(second.left - first.right >= 7.5.dp)
        assertEquals((first.right - first.left).value, (second.right - second.left).value, 1f)
    }

    @Test
    fun sharedFieldKeepsPasswordMaskAndReadOnlyValue() {
        val value = mutableStateOf("")
        composeTestRule.setContent {
            PageHarborTheme(darkTheme = true) {
                Column {
                    RmeTextField(value.value, { value.value = it }, label = { Text("Password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("password"))
                    RmeTextField("Source text", {}, readOnly = true, label = { Text("Original") },
                        modifier = Modifier.testTag("original"))
                }
            }
        }
        composeTestRule.onNodeWithTag("password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("sample")
        assertEquals("sample", value.value)
        composeTestRule.onNodeWithTag("original").assertTextContains("Source text")
    }
}
