package org.synapseworks.pageharbor.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.R

class ReviewConfirmationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelAndCheckboxFormOneControlAndHonorTheReviewGate() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(false)
        compose.setContent {
            ReviewConfirmation(R.string.photo_review_confirmation, checked.value, enabled.value) { checked.value = it }
        }
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onAllNodes(checkbox).assertCountEquals(1)
        val row = compose.onNode(checkbox and hasText("I reviewed this restored copy."))
        row.assertHeightIsAtLeast(48.dp).assertIsNotEnabled().assertIsOff()
        row.performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        row.assertIsOff()
        compose.runOnIdle { enabled.value = true }
        row.performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        row.assertIsOn()
        row.performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        row.assertIsOff()
    }
}
