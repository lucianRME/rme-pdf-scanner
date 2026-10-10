package org.synapseworks.pageharbor.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
