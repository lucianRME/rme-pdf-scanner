package org.synapseworks.pageharbor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ocr.OcrUiError
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.ocr.canStartOcr
import org.synapseworks.pageharbor.ocr.failedPageCount
import org.synapseworks.pageharbor.ocr.formatOcrPreview
import org.synapseworks.pageharbor.ocr.textFoundPageCount
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@Composable
fun HomeScreen(
    snackbarHostState: SnackbarHostState,
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    showBuildDetails: Boolean,
    buildTypeLabel: String,
    versionName: String,
    versionCode: Int,
    gitRevision: String,
    showPrivacyInfo: Boolean,
    showAbout: Boolean,
    onScanDocument: () -> Unit,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onPrivacyInfo: () -> Unit,
    onDismissPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
    onDismissAbout: () -> Unit,
    onViewSourceCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val importInProgress = importUiState == DocumentImportUiState.Selecting ||
        importUiState is DocumentImportUiState.Processing
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = PageHarborLayout.compactScreenHorizontalPadding,
                        vertical = PageHarborSpacing.screen,
                    ),
            ) {
                val contentAlignment = if (maxHeight >= PageHarborLayout.homeCenteredContentMinHeight) {
                    Alignment.Center
                } else {
                    Alignment.TopCenter
                }
                Column(
                    modifier = Modifier
                        .align(contentAlignment)
                        .widthIn(max = PageHarborLayout.homeContentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.app_name_short),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        modifier = Modifier
                            .padding(top = PageHarborSpacing.extraLarge)
                            .semantics { heading() },
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        modifier = Modifier.padding(top = PageHarborSpacing.large),
                        text = stringResource(R.string.home_supporting_text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PageHarborSpacing.extraLarge),
                        enabled = scannerSpikeState != ScannerSpikeState.Preparing && !importInProgress,
                        onClick = onScanDocument,
                    ) {
                        Text(text = stringResource(R.string.home_scan_document))
                    }
                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PageHarborSpacing.small),
                        enabled = scannerSpikeState != ScannerSpikeState.Preparing && !importInProgress,
                        onClick = onImportFiles,
                    ) {
                        Text(text = stringResource(R.string.home_import_files))
                    }
                    Text(
                        modifier = Modifier.padding(top = PageHarborSpacing.small),
                        text = stringResource(R.string.home_import_supporting_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    HomeScanStatus(
                        scannerSpikeState = scannerSpikeState,
                        hasActiveSession = hasActiveSession,
                        importUiState = importUiState,
                        onViewScanResult = onViewScanResult,
                        onCancelImport = onCancelImport,
                    )
                    TextButton(
                        modifier = Modifier.padding(top = PageHarborSpacing.small),
                        onClick = onPrivacyInfo,
                    ) {
                        Text(text = stringResource(R.string.home_privacy_action))
                    }
                    TextButton(
                        onClick = onAbout,
                    ) {
                        Text(text = stringResource(R.string.home_about_action))
                    }
                    Text(
                        modifier = Modifier.padding(top = PageHarborSpacing.extraLarge),
                        text = stringResource(R.string.home_footer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (showBuildDetails) {
                        Text(
                            modifier = Modifier.padding(top = PageHarborSpacing.medium),
                            text = stringResource(
                                R.string.home_debug_build_label,
                                versionName,
                                versionCode,
                                buildTypeLabel,
                                gitRevision,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (showPrivacyInfo) {
        PrivacyInfoDialog(onDismiss = onDismissPrivacyInfo)
    }

    if (showAbout) {
        AboutDialog(
            showBuildDetails = showBuildDetails,
            buildTypeLabel = buildTypeLabel,
            versionName = versionName,
            versionCode = versionCode,
            gitRevision = gitRevision,
            onViewSourceCode = onViewSourceCode,
            onDismiss = onDismissAbout,
        )
    }
}

@Composable
private fun HomeScanStatus(
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    onViewScanResult: () -> Unit,
    onCancelImport: () -> Unit,
) {
    when {
        importUiState is DocumentImportUiState.Processing -> {
            Row(
                modifier = Modifier
                    .padding(top = PageHarborSpacing.large)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                Text(
                    text = if (importUiState.totalItems > 0 && importUiState.preparedPages > 0) {
                        stringResource(
                            R.string.import_progress_with_pages,
                            importUiState.completedItems,
                            importUiState.totalItems,
                            importUiState.preparedPages,
                        )
                    } else if (importUiState.totalItems > 0) {
                        stringResource(
                            R.string.import_progress,
                            importUiState.completedItems,
                            importUiState.totalItems,
                        )
                    } else {
                        stringResource(R.string.import_progress_preparing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onCancelImport) {
                Text(stringResource(R.string.import_cancel_action))
            }
        }

        scannerSpikeState == ScannerSpikeState.Preparing -> {
            InlineOperationStatus(
                messageRes = R.string.home_scan_preparing,
                modifier = Modifier.padding(top = PageHarborSpacing.large),
            )
        }

        hasActiveSession -> {
            TextButton(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                onClick = onViewScanResult,
            ) {
                Text(text = stringResource(R.string.home_view_scan_result))
            }
        }

        else -> Unit
    }
}

@Composable
private fun ScanResultSummary(
    resultSummary: ScannerSpikeState.ResultSummary,
    pdfSaveState: PdfSaveState,
    pdfShareState: PdfShareState,
    pageExportState: PageExportState,
    ocrUiState: OcrUiState,
    onSavePdf: () -> Unit,
    onSharePdf: () -> Unit,
    onExportPages: () -> Unit,
    onRecognizeText: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onClearRecognizedText: () -> Unit,
    onClearScanResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saveInProgress = pdfSaveState == PdfSaveState.ChoosingDestination ||
        pdfSaveState == PdfSaveState.Saving
    val shareInProgress = pdfShareState == PdfShareState.Preparing
    val pageExportProgress = when (pageExportState) {
        is PageExportState.ChoosingDestination -> pageExportState
        is PageExportState.Exporting -> pageExportState
        else -> null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.home_scan_result_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                R.string.home_scan_result_jpeg_pages,
                resultSummary.jpegPageCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (resultSummary.hasPdf) {
                stringResource(R.string.home_scan_result_pdf_returned)
            } else {
                stringResource(R.string.home_scan_result_pdf_not_returned)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        resultSummary.pdfPageCount?.let { pdfPageCount ->
            Text(
                text = stringResource(R.string.home_scan_result_pdf_pages, pdfPageCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.home_scan_result_local_statement),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (resultSummary.hasPdf) {
            Button(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                enabled = !saveInProgress,
                onClick = onSavePdf,
            ) {
                Text(text = stringResource(R.string.pdf_save_action))
            }
        }
        if (resultSummary.hasPdf || resultSummary.jpegPageCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                if (resultSummary.hasPdf) {
                    OutlinedButton(
                        enabled = !shareInProgress,
                        onClick = onSharePdf,
                    ) {
                        Text(text = stringResource(R.string.pdf_share_action))
                    }
                }
                if (resultSummary.jpegPageCount > 0) {
                    OutlinedButton(
                        enabled = pageExportProgress == null,
                        onClick = onExportPages,
                    ) {
                        Text(text = stringResource(R.string.page_export_action))
                    }
                }
            }
        }
        if (saveInProgress) {
            Row(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                Text(
                    text = stringResource(R.string.pdf_save_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (shareInProgress) {
            Row(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                Text(
                    text = stringResource(R.string.pdf_share_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (pageExportProgress != null) {
            val pageNumber = when (pageExportProgress) {
                is PageExportState.ChoosingDestination -> pageExportProgress.pageNumber
                is PageExportState.Exporting -> pageExportProgress.pageNumber
                else -> error("Unexpected page export state")
            }
            val pageCount = when (pageExportProgress) {
                is PageExportState.ChoosingDestination -> pageExportProgress.pageCount
                is PageExportState.Exporting -> pageExportProgress.pageCount
                else -> error("Unexpected page export state")
            }
            Row(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                Text(
                    text = stringResource(
                        R.string.page_export_progress,
                        pageNumber,
                        pageCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (pdfSaveState == PdfSaveState.Saved) {
            Text(
                text = stringResource(R.string.pdf_save_success),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (pageExportState is PageExportState.Completed) {
            Text(
                text = stringResource(R.string.page_export_success),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (pageExportState is PageExportState.Cancelled) {
            Text(
                text = stringResource(R.string.page_export_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (resultSummary.jpegPageCount > 0) {
            OutlinedButton(
                enabled = canStartOcr(ocrUiState),
                onClick = onRecognizeText,
            ) {
                Text(
                    text = stringResource(
                        if (ocrUiState is OcrUiState.Success) {
                            R.string.ocr_recognize_again_action
                        } else {
                            R.string.ocr_recognize_action
                        },
                    ),
                )
            }
        }
        OcrResultSection(
            state = ocrUiState,
            onViewRecognizedText = onViewRecognizedText,
            onClearRecognizedText = onClearRecognizedText,
        )
        OutlinedButton(
            modifier = Modifier.padding(top = PageHarborSpacing.small),
            onClick = onClearScanResult,
        ) {
            Text(text = stringResource(R.string.home_clear_scan_result))
        }
    }
}

@Composable
private fun OcrResultSection(
    state: OcrUiState,
    onViewRecognizedText: () -> Unit,
    onClearRecognizedText: () -> Unit,
) {
    when (state) {
        OcrUiState.Idle -> Unit

        OcrUiState.Recognizing -> {
            Row(
                modifier = Modifier
                    .padding(top = PageHarborSpacing.small)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                Text(
                    text = stringResource(R.string.ocr_recognizing_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        is OcrUiState.Error -> {
            val message = when (state.reason) {
                OcrUiError.NO_PAGES -> stringResource(R.string.ocr_error_no_pages)
                OcrUiError.ALL_PAGES_FAILED -> stringResource(R.string.ocr_error_all_pages_failed)
                OcrUiError.UNEXPECTED_FAILURE -> stringResource(R.string.ocr_error_unexpected)
            }
            Text(
                modifier = Modifier.padding(top = PageHarborSpacing.small),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        is OcrUiState.Success -> {
            OutlinedButton(onClick = onViewRecognizedText) {
                Text(text = stringResource(R.string.ocr_view_action))
            }
        }
    }
}

@Composable
internal fun PrivacyInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.home_privacy_dialog_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = PageHarborLayout.scrollableDialogContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
            ) {
                Text(text = stringResource(R.string.home_privacy_dialog_local_processing))
                Text(text = stringResource(R.string.home_privacy_dialog_library))
                Text(text = stringResource(R.string.home_privacy_dialog_no_cloud))
                Text(text = stringResource(R.string.home_privacy_dialog_user_choice))
                Text(text = stringResource(R.string.home_privacy_dialog_no_tracking))
                Text(text = stringResource(R.string.home_privacy_dialog_mlkit_metrics))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.home_privacy_dialog_dismiss))
            }
        }
    )
}

@Composable
internal fun AboutDialog(
    showBuildDetails: Boolean,
    buildTypeLabel: String,
    versionName: String,
    versionCode: Int,
    gitRevision: String,
    onViewSourceCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.about_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = PageHarborLayout.scrollableDialogContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.dialog),
            ) {
                Text(text = stringResource(R.string.about_tagline))
                Text(text = stringResource(R.string.about_version, versionName))
                Text(text = stringResource(R.string.about_build_number, versionCode))
                if (showBuildDetails) {
                    Text(text = stringResource(R.string.about_build_type, buildTypeLabel))
                    Text(text = stringResource(R.string.about_git_revision, gitRevision))
                }
                Text(text = stringResource(R.string.about_published_under))
                Text(text = stringResource(R.string.about_license))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.about_close))
            }
        },
        dismissButton = {
            TextButton(onClick = onViewSourceCode) {
                Text(text = stringResource(R.string.about_view_source))
            }
        },
    )
}
