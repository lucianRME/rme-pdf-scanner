package org.synapseworks.pageharbor.ui.portability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@Composable
fun MigrationPreviewScreen(
    preview: MigrationPreviewUiModel,
    onReview: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    importEnabled: Boolean = true,
    showAllReviewItems: Boolean = false,
    onDuplicateDecision: (Long, Boolean) -> Unit = { _, _ -> },
) {
    var reviewPageIndex by rememberSaveable(preview.source, preview.ambiguousGroups.size) {
        mutableIntStateOf(0)
    }
    LaunchedEffect(showAllReviewItems) {
        if (!showAllReviewItems) reviewPageIndex = 0
    }
    PortabilityScreen(
        title = "Move to RME",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = "Review before import",
            supportingText = "RME inspected the items selected from ${preview.source.displayName}. " +
                "Nothing is added to your library until you choose Import.",
        )

        SummaryMetrics(
            metrics = listOf(
                "Documents" to preview.documentCount.toString(),
                "Pages" to preview.pageCount.toString(),
                "Folders" to preview.folderCount.toString(),
                "Exact duplicates" to preview.exactDuplicateCount.toString(),
                "Possible duplicates" to preview.possibleDuplicateCount.toString(),
                "Unsupported files" to preview.unsupportedFileCount.toString(),
                "Estimated storage" to preview.estimatedStorage,
            ),
        )

        if (preview.ambiguousGroups.isNotEmpty()) {
            PortabilitySection(title = "Needs review") {
                Text(
                    text = "Review possible duplicates before importing. Items stay out of the " +
                        "library unless you explicitly choose Import anyway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val visibleGroups = if (showAllReviewItems) {
                    preview.ambiguousGroups.drop(reviewPageIndex * REVIEW_PAGE_SIZE)
                        .take(REVIEW_PAGE_SIZE)
                } else {
                    preview.ambiguousGroups.take(PREVIEW_GROUP_LIMIT)
                }
                visibleGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = group.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        group.duplicateSelectionId?.let { selectionId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = group.importAnyway,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            onDuplicateDecision(selectionId, checked)
                                        },
                                    ),
                            ) {
                                Checkbox(
                                    checked = group.importAnyway,
                                    onCheckedChange = null,
                                )
                                Text(
                                    text = "Import this possible duplicate anyway",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                if (showAllReviewItems) {
                    val first = reviewPageIndex * REVIEW_PAGE_SIZE + 1
                    val last = (first + visibleGroups.size - 1)
                        .coerceAtMost(preview.ambiguousGroups.size)
                    Text(
                        text = "Review items $first–$last of ${preview.ambiguousGroups.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.ambiguousGroups.size > REVIEW_PAGE_SIZE) {
                        PortabilityActions(
                            actions = listOf(
                                PortabilityAction(
                                    label = "Previous items",
                                    onClick = { reviewPageIndex-- },
                                    enabled = reviewPageIndex > 0,
                                ),
                                PortabilityAction(
                                    label = "Next items",
                                    onClick = { reviewPageIndex++ },
                                    enabled = last < preview.ambiguousGroups.size,
                                ),
                            ),
                        )
                    }
                } else {
                    val remaining = preview.ambiguousGroups.size - visibleGroups.size
                    if (remaining > 0) {
                        Text(
                            text = "$remaining more groups are available in Review.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (preview.exactDuplicateCount > 0 || preview.possibleDuplicateCount > 0) {
            InformationCallout(
                text = "Exact duplicates can be skipped. Possible duplicates are never discarded " +
                    "silently and remain available for review.",
            )
        }
        if (preview.unsupportedFileCount > 0) {
            InformationCallout(
                text = countLabel(
                    preview.unsupportedFileCount,
                    singular = "unsupported file will be skipped",
                    plural = "unsupported files will be skipped",
                ),
                isError = true,
            )
        }

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Import",
                    onClick = onImport,
                    enabled = importEnabled && preview.documentCount > 0,
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = if (showAllReviewItems) "Show less" else "Review",
                    onClick = onReview,
                    enabled = preview.ambiguousGroups.isNotEmpty(),
                ),
                PortabilityAction(
                    label = "Cancel",
                    onClick = onCancel,
                    style = PortabilityActionStyle.TEXT,
                ),
            ),
        )
    }
}

@Composable
fun MigrationProgressScreen(
    progress: MigrationProgressUiModel,
    onCancelSafely: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortabilityScreen(
        title = "Moving documents",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = if (progress.cancellationRequested) {
                "Stopping safely"
            } else {
                "Import in progress"
            },
            supportingText = if (progress.cancellationRequested) {
                "RME is finishing the current safe boundary. Completed documents stay complete; " +
                    "a half-published document is never left in your library."
            } else {
                "You can leave completed work in place or ask RME to stop at a safe boundary."
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            LinearProgressIndicator(
                progress = { progress.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${progress.completedDocuments} of ${progress.totalDocuments} documents",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Stage: ${progress.currentStage}",
                style = MaterialTheme.typography.bodyMedium,
            )
            progress.currentDocument?.takeIf { it.isNotBlank() }?.let { currentDocument ->
                Text(
                    text = "Current: $currentDocument",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SummaryMetrics(
            metrics = listOf(
                "Skipped items" to progress.skippedItems.toString(),
                "Failures" to progress.failedItems.toString(),
            ),
        )

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = if (progress.cancellationRequested) "Cancellation requested" else "Cancel safely",
                    onClick = onCancelSafely,
                    enabled = !progress.cancellationRequested,
                ),
            ),
        )
    }
}

@Composable
fun MigrationCompletionScreen(
    report: MigrationCompletionUiModel,
    onViewDocuments: () -> Unit,
    onViewIssues: () -> Unit,
    onRetryFailedItems: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasIssues = report.failedItems > 0 || report.issues.isNotEmpty()
    PortabilityScreen(
        title = "Migration complete",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = if (hasIssues) "Completed with some issues" else "Documents moved to RME",
            supportingText = if (hasIssues) {
                "Successful documents are already available. Retry only failed items without " +
                    "importing successful documents again."
            } else {
                "Your imported documents are now available in the local RME library."
            },
        )

        SummaryMetrics(
            metrics = listOf(
                "Imported" to report.importedDocuments.toString(),
                "Exact duplicates skipped" to report.duplicatesSkipped.toString(),
                "Failed" to report.failedItems.toString(),
            ),
        )

        if (report.issues.isNotEmpty()) {
            PortabilitySection(title = "Issues") {
                report.issues.take(ISSUE_PREVIEW_LIMIT).forEachIndexed { index, issue ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
                        Text(
                            text = issue.itemLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = issue.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (report.issues.size > ISSUE_PREVIEW_LIMIT) {
                    Text(
                        text = "${report.issues.size - ISSUE_PREVIEW_LIMIT} more issues",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val actions = buildList {
            add(
                PortabilityAction(
                    label = "View documents",
                    onClick = onViewDocuments,
                    style = PortabilityActionStyle.PRIMARY,
                ),
            )
            if (hasIssues) {
                add(PortabilityAction(label = "View issues", onClick = onViewIssues))
                add(
                    PortabilityAction(
                        label = "Retry failed items",
                        onClick = onRetryFailedItems,
                        enabled = report.failedItems > 0 && report.issues.any { it.retryable },
                    ),
                )
            }
            add(
                PortabilityAction(
                    label = "Done",
                    onClick = onDone,
                    style = PortabilityActionStyle.TEXT,
                ),
            )
        }
        PortabilityActions(actions = actions)
    }
}

@Composable
fun MigrationIssuesScreen(
    issues: List<MigrationIssueUiModel>,
    onRetryFailedItems: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PortabilityScreen(
        title = "Migration issues",
        onBack = onBack,
        modifier = modifier,
    ) {
        PortabilityIntro(
            title = countLabel(issues.size, "item needs attention", "items need attention"),
            supportingText = "Successful imports are not repeated. Retry acts only on failed items " +
                "that can be tried again.",
        )

        if (issues.isEmpty()) {
            InformationCallout(text = "There are no migration issues to review.")
        } else {
            PortabilitySection(title = "Details") {
                issues.take(ISSUE_SCREEN_LIMIT).forEachIndexed { index, issue ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
                        Text(
                            text = issue.itemLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = issue.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (issue.retryable) "Can retry" else "Cannot retry automatically",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (issue.retryable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (issues.size > ISSUE_SCREEN_LIMIT) {
                    Text(
                        text = "${issues.size - ISSUE_SCREEN_LIMIT} additional issues are not shown here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PortabilityActions(
            actions = listOf(
                PortabilityAction(
                    label = "Retry failed items",
                    onClick = onRetryFailedItems,
                    enabled = issues.any { it.retryable },
                    style = PortabilityActionStyle.PRIMARY,
                ),
                PortabilityAction(
                    label = "Done",
                    onClick = onDone,
                    style = PortabilityActionStyle.TEXT,
                ),
            ),
        )
    }
}

private const val PREVIEW_GROUP_LIMIT = 3
private const val REVIEW_PAGE_SIZE = 25
private const val ISSUE_PREVIEW_LIMIT = 5
private const val ISSUE_SCREEN_LIMIT = 100
