package org.synapseworks.pageharbor.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.synapseworks.pageharbor.MAX_DOCUMENT_PAGES
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.PageExportState
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.document.PdfSaveState
import org.synapseworks.pageharbor.document.PdfShareState
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfSaveState
import org.synapseworks.pageharbor.document.searchablepdf.isInProgress
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.LibraryDocumentReference
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    result: ScannerSpikeState.ResultSummary,
    snackbarHostState: SnackbarHostState,
    pdfSaveState: PdfSaveState,
    pdfShareState: PdfShareState,
    pageExportState: PageExportState,
    ocrUiState: OcrUiState,
    searchablePdfSaveState: SearchablePdfSaveState,
    documentPages: List<DocumentPage>,
    libraryDocument: LibraryDocumentReference?,
    libraryActionState: LibraryActionState,
    importUiState: DocumentImportUiState,
    onPageFilterChange: (Long, DocumentFilter) -> Unit,
    onPageRotate: (Long) -> Unit,
    onPageMove: (Long, Int) -> Unit,
    onPageRemove: (Long) -> Unit,
    onSaveToLibrary: (String) -> Unit,
    onExtractLibraryPages: (Set<String>, String, Boolean) -> Unit,
    onConsumeLibraryAction: () -> Unit,
    onBack: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onSharePdf: () -> Unit,
    onExportPages: () -> Unit,
    onRecognizeText: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onScanAgain: () -> Unit,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onDiscard: () -> Unit,
) {
    var selectedPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showLibrarySaveDialog by rememberSaveable { mutableStateOf(false) }
    var showPageToolsDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(documentPages) {
        if (documentPages.none { it.id.value == selectedPageId }) {
            selectedPageId = documentPages.firstOrNull()?.id?.value
        }
    }
    val selectedPageIndex = documentPages.indexOfFirst { it.id.value == selectedPageId }
        .takeIf { it >= 0 } ?: 0
    val selectedPage = documentPages.getOrNull(selectedPageIndex)
    val displayedPageCount = documentPages.size.takeIf { it > 0 } ?: result.jpegPageCount
    val hasProcessablePages = documentPages.isNotEmpty()
    val canAddPages = displayedPageCount < MAX_DOCUMENT_PAGES
    val saving = pdfSaveState == PdfSaveState.ChoosingDestination ||
        pdfSaveState == PdfSaveState.Saving
    val sharing = pdfShareState == PdfShareState.Preparing
    val exporting = pageExportState is PageExportState.ChoosingDestination ||
        pageExportState is PageExportState.Exporting
    val savingSearchablePdf = searchablePdfSaveState.isInProgress()
    val importing = importUiState == DocumentImportUiState.Selecting ||
        importUiState is DocumentImportUiState.Processing
    val libraryWorking = libraryActionState == LibraryActionState.Working
    val libraryMessage = libraryActionMessage(libraryActionState)
    val libraryEventId = when (libraryActionState) {
        is LibraryActionState.Succeeded -> libraryActionState.eventId
        is LibraryActionState.Failed -> libraryActionState.eventId
        LibraryActionState.Idle,
        LibraryActionState.Working,
        -> null
    }

    LaunchedEffect(libraryEventId) {
        if (libraryEventId != null && libraryMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(libraryMessage)
            onConsumeLibraryAction()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.document_result_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = PageHarborLayout.expandedContentMaxWidth)
                    .fillMaxWidth()
                    .padding(
                        horizontal = PageHarborLayout.compactScreenHorizontalPadding,
                        vertical = PageHarborSpacing.large,
                    )
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
            ) {
                ScanContext(displayedPageCount)

                selectedPage?.let { page ->
                    PageEditingSection(
                        page = page,
                        selectedPageIndex = selectedPageIndex,
                        pageCount = documentPages.size,
                        canAddPages = canAddPages,
                        onSelectedPageChange = { index -> selectedPageId = documentPages[index].id.value },
                        onPageFilterChange = onPageFilterChange,
                        onAddPages = onScanAgain,
                        onImportFiles = onImportFiles,
                        actionsEnabled = !importing && !libraryWorking,
                    )
                } ?: PageToolbar(
                    selectedPageIndex = null,
                    pageCount = displayedPageCount,
                    canAddPages = canAddPages,
                    onSelectedPageChange = {},
                    onAddPages = onScanAgain,
                    onImportFiles = onImportFiles,
                    actionsEnabled = !importing && !libraryWorking,
                )

                DocumentActionLayer(
                    canExportPdf = hasProcessablePages,
                    hasPages = hasProcessablePages,
                    saving = saving,
                    savingSearchablePdf = savingSearchablePdf,
                    sharing = sharing,
                    exporting = exporting,
                    importing = importing,
                    libraryDocument = libraryDocument,
                    libraryWorking = libraryWorking,
                    ocrUiState = ocrUiState,
                    onSavePdf = onSavePdf,
                    onSaveSearchablePdf = onSaveSearchablePdf,
                    onSharePdf = onSharePdf,
                    onExportPages = onExportPages,
                    onRecognizeText = onRecognizeText,
                    onViewRecognizedText = onViewRecognizedText,
                    onSaveToLibrary = { showLibrarySaveDialog = true },
                    onOpenPageTools = { showPageToolsDialog = true },
                )

                selectedPage?.let { page ->
                    PageActions(
                        page = page,
                        selectedPageIndex = selectedPageIndex,
                        pageCount = documentPages.size,
                        actionsEnabled = !importing && !libraryWorking,
                        onPageRotate = onPageRotate,
                        onPageMove = onPageMove,
                        onPageRemove = onPageRemove,
                    )
                }

                OperationStatus(
                    pdfSaveState = pdfSaveState,
                    searchablePdfSaveState = searchablePdfSaveState,
                    sharing = sharing,
                    pageExportState = pageExportState,
                    ocrUiState = ocrUiState,
                    importUiState = importUiState,
                    libraryWorking = libraryWorking,
                    onCancelImport = onCancelImport,
                )

                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !libraryWorking,
                    onClick = onDiscard,
                ) {
                    Text(stringResource(R.string.home_clear_scan_result))
                }
            }
        }
    }

    if (showLibrarySaveDialog) {
        SaveToLibraryDialog(
            initialTitle = libraryDocument?.title.orEmpty(),
            isUpdate = libraryDocument != null,
            onDismiss = { showLibrarySaveDialog = false },
            onSave = { title ->
                onSaveToLibrary(title)
                showLibrarySaveDialog = false
            },
        )
    }

    if (showPageToolsDialog && libraryDocument != null) {
        LibraryPageToolsDialog(
            pages = documentPages,
            onDismiss = { showPageToolsDialog = false },
            onApply = { pageIds, title, removeFromOriginal ->
                onExtractLibraryPages(pageIds, title, removeFromOriginal)
                showPageToolsDialog = false
            },
        )
    }
}

@Composable
private fun ScanContext(pageCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.document_ready),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (pageCount == 1) R.string.scan_page_ready else R.string.scan_pages_ready,
                pageCount,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PageEditingSection(
    page: DocumentPage,
    selectedPageIndex: Int,
    pageCount: Int,
    canAddPages: Boolean,
    onSelectedPageChange: (Int) -> Unit,
    onPageFilterChange: (Long, DocumentFilter) -> Unit,
    onAddPages: () -> Unit,
    onImportFiles: () -> Unit,
    actionsEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium)) {
        FilteredDocumentPreview(
            request = FilteredPreviewRequest(
                pageId = page.id.value,
                sourceKey = page.source.reference,
                filter = page.filter,
                rotation = page.rotation,
                imageMetadata = page.imageMetadata,
            ),
            pageUri = page.source.toAndroidUri(),
            pageNumber = selectedPageIndex + 1,
            pageCount = pageCount,
            minHeight = PageHarborLayout.editorDocumentPreviewMinHeight,
            maxHeight = PageHarborLayout.editorDocumentPreviewMaxHeight,
        )
        PageToolbar(
            selectedPageIndex = selectedPageIndex,
            pageCount = pageCount,
            canAddPages = canAddPages,
            onSelectedPageChange = onSelectedPageChange,
            onAddPages = onAddPages,
            onImportFiles = onImportFiles,
            actionsEnabled = actionsEnabled,
        )
        FilterSelector(
            selectedFilter = page.filter,
            enabled = actionsEnabled,
            onFilterSelected = { onPageFilterChange(page.id.value, it) },
        )
    }
}

@Composable
private fun PageActions(
    page: DocumentPage,
    selectedPageIndex: Int,
    pageCount: Int,
    actionsEnabled: Boolean,
    onPageRotate: (Long) -> Unit,
    onPageMove: (Long, Int) -> Unit,
    onPageRemove: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.page_actions_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = actionsEnabled,
            onClick = { onPageRotate(page.id.value) },
        ) { Text(stringResource(R.string.page_rotate_action)) }
        AdaptiveActionPair(
            first = { modifier ->
                TextButton(
                    modifier = modifier,
                    enabled = actionsEnabled && selectedPageIndex > 0,
                    onClick = { onPageMove(page.id.value, -1) },
                ) { Text(stringResource(R.string.page_move_earlier_action)) }
            },
            second = { modifier ->
                TextButton(
                    modifier = modifier,
                    enabled = actionsEnabled && selectedPageIndex < pageCount - 1,
                    onClick = { onPageMove(page.id.value, 1) },
                ) { Text(stringResource(R.string.page_move_later_action)) }
            },
        )
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = actionsEnabled && pageCount > 1,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            onClick = { onPageRemove(page.id.value) },
        ) {
            Text(stringResource(R.string.page_remove_action))
        }
        if (pageCount <= 1) {
            Text(
                text = stringResource(R.string.page_remove_final_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentActionLayer(
    canExportPdf: Boolean,
    hasPages: Boolean,
    saving: Boolean,
    savingSearchablePdf: Boolean,
    sharing: Boolean,
    exporting: Boolean,
    importing: Boolean,
    libraryDocument: LibraryDocumentReference?,
    libraryWorking: Boolean,
    onSavePdf: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onSharePdf: () -> Unit,
    onExportPages: () -> Unit,
    ocrUiState: OcrUiState,
    onRecognizeText: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onOpenPageTools: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            if (canExportPdf) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && !importing && !libraryWorking,
                    onClick = onSavePdf,
                ) {
                    Text(stringResource(R.string.pdf_save_action))
                }
            }
            if (hasPages) {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !libraryWorking && !saving && !savingSearchablePdf && !sharing &&
                        !exporting && !importing && ocrUiState != OcrUiState.Recognizing,
                    onClick = onSaveToLibrary,
                ) {
                    Text(
                        stringResource(
                            if (libraryDocument == null) {
                                R.string.library_save_action
                            } else {
                                R.string.library_save_changes_action
                            },
                        ),
                    )
                }
            }
            if (libraryDocument != null) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !libraryWorking && !importing,
                    onClick = onOpenPageTools,
                ) {
                    Text(stringResource(R.string.library_page_tools_action))
                }
            }
            if (hasPages) {
                AdaptiveActionPair(
                    first = { modifier ->
                        FilledTonalButton(
                            modifier = modifier,
                            enabled = ocrUiState != OcrUiState.Recognizing && !importing && !libraryWorking,
                            onClick = onRecognizeText,
                        ) {
                            Text(
                                stringResource(
                                    if (ocrUiState is OcrUiState.Success) {
                                        R.string.ocr_recognize_again_action
                                    } else {
                                        R.string.ocr_recognize_action
                                    },
                                ),
                            )
                        }
                    },
                    second = { modifier ->
                        TextButton(
                            modifier = modifier,
                            enabled = !savingSearchablePdf && !importing && !libraryWorking,
                            onClick = onSaveSearchablePdf,
                        ) {
                            Text(stringResource(R.string.searchable_pdf_save_action))
                        }
                    },
                )
            }
            if (hasPages && ocrUiState is OcrUiState.Success) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onViewRecognizedText,
                ) {
                    Text(stringResource(R.string.ocr_view_action))
                }
            }
            if (canExportPdf || hasPages) {
                AdaptiveActionPair(
                    first = { modifier ->
                        TextButton(
                            modifier = modifier,
                            enabled = !sharing && !importing && !libraryWorking,
                            onClick = onSharePdf,
                        ) {
                            Text(stringResource(R.string.pdf_share_action))
                        }
                    },
                    second = { modifier ->
                        TextButton(
                            modifier = modifier,
                            enabled = !exporting && !importing && !libraryWorking,
                            onClick = onExportPages,
                        ) {
                            Text(stringResource(R.string.page_export_action))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PageToolbar(
    selectedPageIndex: Int?,
    pageCount: Int,
    canAddPages: Boolean,
    onSelectedPageChange: (Int) -> Unit,
    onAddPages: () -> Unit,
    onImportFiles: () -> Unit,
    actionsEnabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            if (selectedPageIndex != null && pageCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = selectedPageIndex > 0,
                        onClick = { onSelectedPageChange(selectedPageIndex - 1) },
                    ) {
                        Text(stringResource(R.string.ocr_previous_page_action))
                    }
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        text = stringResource(
                            R.string.ocr_page_indicator,
                            selectedPageIndex + 1,
                            pageCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = selectedPageIndex < pageCount - 1,
                        onClick = { onSelectedPageChange(selectedPageIndex + 1) },
                    ) {
                        Text(stringResource(R.string.ocr_next_page_action))
                    }
                }
            }
            AdaptiveActionPair(
                first = { modifier -> TextButton(
                    modifier = modifier,
                    enabled = canAddPages && actionsEnabled,
                    onClick = onAddPages,
                ) {
                    Text(stringResource(R.string.scan_again_action))
                } },
                second = { modifier -> TextButton(
                    modifier = modifier,
                    enabled = canAddPages && actionsEnabled,
                    onClick = onImportFiles,
                ) {
                    Text(stringResource(R.string.import_add_files_action))
                } },
            )
            if (!canAddPages) {
                Text(
                    text = stringResource(R.string.scan_page_limit_reached),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdaptiveActionPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stack = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}
@Composable
private fun OperationStatus(
    pdfSaveState: PdfSaveState,
    searchablePdfSaveState: SearchablePdfSaveState,
    sharing: Boolean,
    pageExportState: PageExportState,
    ocrUiState: OcrUiState,
    importUiState: DocumentImportUiState,
    libraryWorking: Boolean,
    onCancelImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        if (libraryWorking) {
            InlineOperationStatus(R.string.library_working)
        }
        if (pdfSaveState == PdfSaveState.Saving) {
            InlineOperationStatus(R.string.pdf_save_progress)
        }
        when (searchablePdfSaveState) {
            SearchablePdfSaveState.Preparing -> {
                InlineOperationStatus(R.string.searchable_pdf_preparing_progress)
            }

            SearchablePdfSaveState.Recognizing -> {
                InlineOperationStatus(R.string.searchable_pdf_recognizing_progress)
            }

            SearchablePdfSaveState.Generating -> {
                InlineOperationStatus(R.string.searchable_pdf_generating_progress)
            }

            SearchablePdfSaveState.Saving -> {
                InlineOperationStatus(R.string.searchable_pdf_saving_progress)
            }

            SearchablePdfSaveState.Idle,
            SearchablePdfSaveState.ChoosingDestination,
            SearchablePdfSaveState.Saved,
            SearchablePdfSaveState.Cancelled,
            is SearchablePdfSaveState.Error,
            -> Unit
        }
        if (sharing) {
            InlineOperationStatus(R.string.pdf_share_progress)
        }
        when (pageExportState) {
            is PageExportState.Exporting -> InlineOperationStatus(
                R.string.page_export_progress,
                pageExportState.pageNumber,
                pageExportState.pageCount,
            )

            PageExportState.Idle,
            is PageExportState.ChoosingDestination,
            is PageExportState.Cancelled,
            is PageExportState.Completed,
            is PageExportState.Error,
            -> Unit
        }
        when (ocrUiState) {
            OcrUiState.Recognizing -> InlineOperationStatus(R.string.ocr_recognizing_progress)

            OcrUiState.Idle,
            is OcrUiState.Error,
            is OcrUiState.Success,
            -> Unit
        }
        if (importUiState is DocumentImportUiState.Processing) {
            if (importUiState.totalItems > 0) {
                if (importUiState.preparedPages > 0) {
                    InlineOperationStatus(
                        R.string.import_progress_with_pages,
                        importUiState.completedItems,
                        importUiState.totalItems,
                        importUiState.preparedPages,
                    )
                } else {
                    InlineOperationStatus(
                        R.string.import_progress,
                        importUiState.completedItems,
                        importUiState.totalItems,
                    )
                }
            } else {
                InlineOperationStatus(R.string.import_progress_preparing)
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancelImport,
            ) {
                Text(stringResource(R.string.import_cancel_action))
            }
        }
    }
}

@Composable
private fun FilterSelector(
    selectedFilter: DocumentFilter,
    enabled: Boolean,
    onFilterSelected: (DocumentFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
        Text(
            text = stringResource(R.string.filter_selector_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            filterSelectorOptions.forEach { option ->
                val accessibleLabel = stringResource(option.contentDescriptionRes)
                FilterChip(
                    selected = option.filter == selectedFilter,
                    enabled = enabled,
                    onClick = { onFilterSelected(option.filter) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.semantics {
                        contentDescription = accessibleLabel
                    },
                )
            }
        }
    }
}

@Composable
private fun SaveToLibraryDialog(
    initialTitle: String,
    isUpdate: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by rememberSaveable(initialTitle) {
        mutableStateOf(initialTitle.ifBlank { "Document" })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isUpdate) R.string.library_save_changes_title else R.string.library_save_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_document_title_label)) },
                )
                Text(
                    text = stringResource(R.string.library_save_supporting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title) }) {
                Text(
                    stringResource(
                        if (isUpdate) {
                            R.string.library_save_changes_action
                        } else {
                            R.string.library_save_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun LibraryPageToolsDialog(
    pages: List<DocumentPage>,
    onDismiss: () -> Unit,
    onApply: (Set<String>, String, Boolean) -> Unit,
) {
    var selectedIds by remember(pages) { mutableStateOf<Set<String>>(emptySet()) }
    var title by rememberSaveable { mutableStateOf("Extracted pages") }
    val allPagesPersisted = pages.all { it.persistentId != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_page_tools_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                Text(stringResource(R.string.library_page_tools_supporting))
                if (!allPagesPersisted) {
                    Text(
                        text = stringResource(R.string.library_page_tools_save_first),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                pages.forEachIndexed { index, page ->
                    val persistentId = page.persistentId
                    FilterChip(
                        selected = persistentId != null && persistentId in selectedIds,
                        enabled = persistentId != null,
                        onClick = {
                            if (persistentId != null) {
                                selectedIds = if (persistentId in selectedIds) {
                                    selectedIds - persistentId
                                } else {
                                    selectedIds + persistentId
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.library_page_number, index + 1)) },
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_document_title_label)) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIds.isNotEmpty() && title.isNotBlank() && allPagesPersisted,
                    onClick = { onApply(selectedIds, title, false) },
                ) {
                    Text(stringResource(R.string.library_extract_copy_action))
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIds.isNotEmpty() && selectedIds.size < pages.size &&
                        title.isNotBlank() && allPagesPersisted,
                    onClick = { onApply(selectedIds, title, true) },
                ) {
                    Text(stringResource(R.string.library_split_move_action))
                }
                Text(
                    text = stringResource(R.string.library_split_safety),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        },
    )
}
