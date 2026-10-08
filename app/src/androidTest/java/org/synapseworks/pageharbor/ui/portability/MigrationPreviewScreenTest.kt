package org.synapseworks.pageharbor.ui.portability

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

class MigrationPreviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewLabelsSourceAndPersistentStorageWithoutCallingPreflightStorage() {
        composeRule.setContent {
            PageHarborTheme {
                MigrationPreviewScreen(
                    preview = MigrationPreviewUiModel(
                        source = ScannerMigrationSource.OTHER,
                        documentCount = 1,
                        pageCount = 8,
                        folderCount = 0,
                        exactDuplicateCount = 0,
                        possibleDuplicateCount = 0,
                        unsupportedFileCount = 0,
                        sourceSize = "7.16 MiB",
                        estimatedLibraryStorage = "10.66 MiB",
                    ),
                    onReview = {},
                    onImport = {},
                    onCancel = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Source size").assertExists()
        composeRule.onNodeWithText("7.16 MiB").assertExists()
        composeRule.onNodeWithText("Estimated library storage").assertExists()
        composeRule.onNodeWithText("10.66 MiB").assertExists()
        composeRule.onAllNodesWithText("Estimated storage").assertCountEquals(0)
    }
}
