package org.synapseworks.pageharbor.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.synapseworks.pageharbor.library.LibraryFolder
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
    libraryFolders: List<LibraryFolder>,
    libraryActionState: LibraryActionState,
    importUiState: DocumentImportUiState,
    onPageFilterChange: (Long, DocumentFilter) -> Unit,
    onPageRotate: (Long) -> Unit,
    onPageMove: (Long, Int) -> Unit,
    onPageRemove: (Long) -> Unit,
    onSaveToLibrary: (String) -> Unit,
    onExtractLibraryPages: (Set<String>, String, Boolean) -> Unit,
    onRenameLibraryDocument: (String, String) -> Unit,
    onMoveLibraryDocument: (String, String?) -> Unit,
    onDeleteLibraryDocument: (String) -> Unit,
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
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<DocumentSheet?>(null) }
    var editPanel by rememberSaveable { mutableStateOf(DocumentEditPanel.None) }
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
    val ocrRecognizing = ocrUiState == OcrUiState.Recognizing
    val sessionActionsEnabled = !importing && !libraryWorking
    val libraryMessage = libraryActionMessage(libraryActionState)
    val libraryEventId = when (libraryActionState) {
        is LibraryActionState.Succeeded -> libraryActionState.eventId
        is LibraryActionState.Failed -> libraryActionState.eventId
        LibraryActionState.Idle,
        LibraryActionState.Working,
        -> null
    }

    BackHandler(enabled = editPanel != DocumentEditPanel.None) {
        editPanel = when (editPanel) {
            DocumentEditPanel.Filters -> DocumentEditPanel.Tools
            DocumentEditPanel.Tools,
            DocumentEditPanel.None,
            -> DocumentEditPanel.None
        }
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
                        text = libraryDocument?.title
                            ?: stringResource(R.string.document_result_title),
                        maxLines = 1,
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
        bottomBar = {
            when (editPanel) {
                DocumentEditPanel.None -> DocumentBottomBar(
                    canAddPages = sessionActionsEnabled,
                    canEdit = selectedPage != null && sessionActionsEnabled,
                    canRecognize = hasProcessablePages && !ocrRecognizing && !importing &&
                        !libraryWorking,
                    canShare = hasProcessablePages && !sharing && !importing && !libraryWorking,
                    onAdd = { activeSheet = DocumentSheet.Add },
                    onEdit = { editPanel = DocumentEditPanel.Tools },
                    onOcr = {
                        if (ocrUiState is OcrUiState.Success) onViewRecognizedText()
                        else onRecognizeText()
                    },
                    onShare = onSharePdf,
                    onMore = { activeSheet = DocumentSheet.More },
                )

                DocumentEditPanel.Tools,
                DocumentEditPanel.Filters,
                -> DocumentEditBar(
                    panel = editPanel,
                    page = selectedPage,
                    selectedPageIndex = selectedPageIndex,
                    pageCount = documentPages.size,
                    enabled = !importing && !libraryWorking,
                    onDone = { editPanel = DocumentEditPanel.None },
                    onBackToTools = { editPanel = DocumentEditPanel.Tools },
                    onOpenFilters = { editPanel = DocumentEditPanel.Filters },
                    onFilterSelected = { page, filter ->
                        onPageFilterChange(page.id.value, filter)
                    },
                    onRotate = { page -> onPageRotate(page.id.value) },
                    onMove = { page, offset -> onPageMove(page.id.value, offset) },
                    onRemove = { page -> onPageRemove(page.id.value) },
                )
            }
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
                        vertical = PageHarborSpacing.small,
                    ),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                selectedPage?.let { page ->
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
                        pageCount = documentPages.size,
                        modifier = Modifier.weight(1f),
                        minHeight = PageHarborLayout.editorDocumentPreviewMinHeight,
                        maxHeight = PageHarborLayout.editorDocumentPreviewMaxHeight,
                    )
                } ?: EmptyDocumentWorkspace(
                    modifier = Modifier.weight(1f),
                    pageCount = displayedPageCount,
                )

                PageNavigator(
                    selectedPageIndex = selectedPageIndex,
                    pageCount = documentPages.size,
                    onSelectedPageChange = { index ->
                        selectedPageId = documentPages[index].id.value
                    },
                )

                if (
                    libraryWorking || saving || savingSearchablePdf || sharing || exporting ||
                    ocrRecognizing || importUiState is DocumentImportUiState.Processing
                ) {
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
                }
            }
        }
    }

    when (activeSheet) {
        DocumentSheet.Add -> DocumentAddSheet(
            canAddPages = canAddPages && sessionActionsEnabled,
            onDismiss = { activeSheet = null },
            onAddPages = {
                activeSheet = null
                onScanAgain()
            },
            onImportFiles = {
                activeSheet = null
                onImportFiles()
            },
        )

        DocumentSheet.More -> DocumentMoreSheet(
            hasPages = hasProcessablePages,
            librarySaveEnabled = sessionActionsEnabled && !saving && !savingSearchablePdf &&
                !sharing && !exporting && !ocrRecognizing,
            pdfExportEnabled = sessionActionsEnabled && !saving,
            searchablePdfEnabled = sessionActionsEnabled && !savingSearchablePdf,
            pageExportEnabled = sessionActionsEnabled && !exporting,
            secondaryActionsEnabled = sessionActionsEnabled,
            libraryDocument = libraryDocument,
            ocrAvailable = ocrUiState is OcrUiState.Success,
            onDismiss = { activeSheet = null },
            onSaveToLibrary = {
                activeSheet = null
                showLibrarySaveDialog = true
            },
            onSavePdf = {
                activeSheet = null
                onSavePdf()
            },
            onSaveSearchablePdf = {
                activeSheet = null
                onSaveSearchablePdf()
            },
            onExportPages = {
                activeSheet = null
                onExportPages()
            },
            onOpenPageTools = {
                activeSheet = null
                showPageToolsDialog = true
            },
            onViewRecognizedText = {
                activeSheet = null
                onViewRecognizedText()
            },
            onRename = {
                activeSheet = null
                showRenameDialog = true
            },
            onMove = {
                activeSheet = null
                showMoveDialog = true
            },
            onDelete = {
                activeSheet = null
                showDeleteDialog = true
            },
            onDiscard = {
                activeSheet = null
                onDiscard()
            },
        )

        null -> Unit
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

    if (showRenameDialog && libraryDocument != null) {
        RenameDocumentDialog(
            initialTitle = libraryDocument.title,
            onDismiss = { showRenameDialog = false },
            onRename = { title ->
                onRenameLibraryDocument(libraryDocument.documentId, title)
                showRenameDialog = false
            },
        )
    }

    if (showMoveDialog && libraryDocument != null) {
        MoveLibraryDocumentDialog(
            folders = libraryFolders,
            onDismiss = { showMoveDialog = false },
            onMove = { folderId ->
                onMoveLibraryDocument(libraryDocument.documentId, folderId)
                showMoveDialog = false
            },
        )
    }

    if (showDeleteDialog && libraryDocument != null) {
        DeleteLibraryDocumentDialog(
            title = libraryDocument.title,
            onDismiss = { showDeleteDialog = false },
            onDelete = {
                onDeleteLibraryDocument(libraryDocument.documentId)
                showDeleteDialog = false
                onDiscard()
            },
        )
    }
}

@Composable
private fun DocumentBottomBar(
    canAddPages: Boolean,
    canEdit: Boolean,
    canRecognize: Boolean,
    canShare: Boolean,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onOcr: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    val labelStyle = if (LocalDensity.current.fontScale >= 1.8f) {
        MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp)
    } else {
        MaterialTheme.typography.labelMedium
    }
    NavigationBar {
        listOf(
            DocumentBarAction(Icons.Default.Add, R.string.document_action_add, canAddPages, onAdd),
            DocumentBarAction(Icons.Default.Edit, R.string.document_action_edit, canEdit, onEdit),
            DocumentBarAction(Icons.Default.TextFields, R.string.document_action_ocr, canRecognize, onOcr),
            DocumentBarAction(Icons.Default.Share, R.string.document_action_share, canShare, onShare),
            DocumentBarAction(Icons.Default.MoreHoriz, R.string.document_action_more, true, onMore),
        ).forEach { action ->
            NavigationBarItem(
                selected = false,
                enabled = action.enabled,
                onClick = action.onClick,
                icon = { Icon(action.icon, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(action.labelResource),
                        maxLines = 1,
                        style = labelStyle,
                    )
                },
            )
        }
    }
}

@Composable
private fun DocumentEditBar(
    panel: DocumentEditPanel,
    page: DocumentPage?,
    selectedPageIndex: Int,
    pageCount: Int,
    enabled: Boolean,
    onDone: () -> Unit,
    onBackToTools: () -> Unit,
    onOpenFilters: () -> Unit,
    onFilterSelected: (DocumentPage, DocumentFilter) -> Unit,
    onRotate: (DocumentPage) -> Unit,
    onMove: (DocumentPage, Int) -> Unit,
    onRemove: (DocumentPage) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(
                    horizontal = PageHarborSpacing.medium,
                    vertical = PageHarborSpacing.small,
                ),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (panel == DocumentEditPanel.Filters) {
                    IconButton(onClick = onBackToTools) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.document_edit_back),
                        )
                    }
                }
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    text = stringResource(
                        if (panel == DocumentEditPanel.Filters) {
                            R.string.document_edit_filters_title
                        } else {
                            R.string.document_edit_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.document_edit_done))
                }
            }
            if (panel == DocumentEditPanel.Filters && page != null) {
                FilterSelector(
                    selectedFilter = page.filter,
                    enabled = enabled,
                    onFilterSelected = { onFilterSelected(page, it) },
                )
            } else {
                Text(
                    text = stringResource(R.string.document_edit_supporting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    EditorActionButton(
                        icon = Icons.Default.FilterAlt,
                        label = stringResource(R.string.document_edit_filter),
                        enabled = page != null && enabled,
                        onClick = onOpenFilters,
                    )
                    EditorActionButton(
                        icon = Icons.AutoMirrored.Filled.RotateRight,
                        label = stringResource(R.string.page_rotate_action),
                        enabled = page != null && enabled,
                        onClick = { page?.let(onRotate) },
                    )
                    EditorActionButton(
                        icon = Icons.AutoMirrored.Filled.Undo,
                        label = stringResource(R.string.page_move_earlier_action),
                        enabled = page != null && enabled && selectedPageIndex > 0,
                        onClick = { page?.let { onMove(it, -1) } },
                    )
                    EditorActionButton(
                        icon = Icons.AutoMirrored.Filled.Redo,
                        label = stringResource(R.string.page_move_later_action),
                        enabled = page != null && enabled && selectedPageIndex < pageCount - 1,
                        onClick = { page?.let { onMove(it, 1) } },
                    )
                    EditorActionButton(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.page_remove_action),
                        enabled = page != null && enabled && pageCount > 1,
                        destructive = true,
                        onClick = { page?.let(onRemove) },
                    )
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
    }
}

@Composable
private fun EditorActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
    ) {
        Icon(
            modifier = Modifier.size(18.dp),
            imageVector = icon,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.size(PageHarborSpacing.small))
        Text(label)
    }
}

@Composable
private fun PageNavigator(
    selectedPageIndex: Int,
    pageCount: Int,
    onSelectedPageChange: (Int) -> Unit,
) {
    if (pageCount <= 0) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = selectedPageIndex > 0,
            onClick = { onSelectedPageChange(selectedPageIndex - 1) },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.ocr_previous_page_action),
            )
        }
        Text(
            modifier = Modifier
                .widthIn(min = 104.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(R.string.ocr_page_indicator, selectedPageIndex + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
        IconButton(
            enabled = selectedPageIndex < pageCount - 1,
            onClick = { onSelectedPageChange(selectedPageIndex + 1) },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.ocr_next_page_action),
            )
        }
    }
}

@Composable
private fun EmptyDocumentWorkspace(modifier: Modifier, pageCount: Int) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PageHarborLayout.editorDocumentPreviewMinHeight),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(
                    if (pageCount == 1) R.string.scan_page_ready else R.string.scan_pages_ready,
                    pageCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentAddSheet(
    canAddPages: Boolean,
    onDismiss: () -> Unit,
    onAddPages: () -> Unit,
    onImportFiles: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetContentColumn(title = stringResource(R.string.document_add_title)) {
            SheetActionRow(
                icon = Icons.Default.Add,
                title = stringResource(R.string.scan_again_action),
                supportingText = stringResource(R.string.document_add_pages_description),
                enabled = canAddPages,
                onClick = onAddPages,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.UploadFile,
                title = stringResource(R.string.import_add_files_action),
                supportingText = stringResource(R.string.document_add_files_description),
                enabled = canAddPages,
                onClick = onImportFiles,
            )
            if (!canAddPages) {
                Text(
                    modifier = Modifier.padding(PageHarborSpacing.medium),
                    text = stringResource(R.string.scan_page_limit_reached),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentMoreSheet(
    hasPages: Boolean,
    librarySaveEnabled: Boolean,
    pdfExportEnabled: Boolean,
    searchablePdfEnabled: Boolean,
    pageExportEnabled: Boolean,
    secondaryActionsEnabled: Boolean,
    libraryDocument: LibraryDocumentReference?,
    ocrAvailable: Boolean,
    onDismiss: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onExportPages: () -> Unit,
    onOpenPageTools: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDiscard: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetContentColumn(title = stringResource(R.string.document_more_title)) {
            SheetActionRow(
                icon = Icons.Default.Save,
                title = stringResource(
                    if (libraryDocument == null) {
                        R.string.library_save_action
                    } else {
                        R.string.library_save_changes_action
                    },
                ),
                supportingText = stringResource(
                    if (libraryDocument == null) {
                        R.string.document_library_save_description
                    } else {
                        R.string.document_library_update_description
                    },
                ),
                enabled = hasPages && librarySaveEnabled,
                onClick = onSaveToLibrary,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.PictureAsPdf,
                title = stringResource(R.string.document_export_pdf),
                supportingText = stringResource(R.string.document_export_pdf_description),
                enabled = hasPages && pdfExportEnabled,
                onClick = onSavePdf,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.TextFields,
                title = stringResource(R.string.searchable_pdf_save_action),
                supportingText = stringResource(R.string.document_save_searchable_description),
                enabled = hasPages && searchablePdfEnabled,
                onClick = onSaveSearchablePdf,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.page_export_action),
                supportingText = stringResource(R.string.document_export_pages_description),
                enabled = hasPages && pageExportEnabled,
                onClick = onExportPages,
            )
            if (libraryDocument != null) {
                HorizontalDivider()
                SheetActionRow(
                    icon = Icons.Default.ContentCut,
                    title = stringResource(R.string.library_page_tools_action),
                    supportingText = stringResource(R.string.document_page_tools_description),
                    enabled = secondaryActionsEnabled,
                    onClick = onOpenPageTools,
                )
            }
            if (ocrAvailable) {
                HorizontalDivider()
                SheetActionRow(
                    icon = Icons.Default.TextFields,
                    title = stringResource(R.string.ocr_view_action),
                    supportingText = stringResource(R.string.document_view_ocr_description),
                    enabled = secondaryActionsEnabled,
                    onClick = onViewRecognizedText,
                )
            }
            if (libraryDocument != null) {
                HorizontalDivider()
                SheetActionRow(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.library_rename_action),
                    supportingText = stringResource(R.string.document_rename_description),
                    enabled = secondaryActionsEnabled,
                    onClick = onRename,
                )
                HorizontalDivider()
                SheetActionRow(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    title = stringResource(R.string.library_move_action),
                    supportingText = stringResource(R.string.document_move_description),
                    enabled = secondaryActionsEnabled,
                    onClick = onMove,
                )
                HorizontalDivider()
                SheetActionRow(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.library_delete_action),
                    supportingText = stringResource(R.string.document_delete_description),
                    enabled = secondaryActionsEnabled,
                    destructive = true,
                    onClick = onDelete,
                )
            }
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Close,
                title = stringResource(R.string.home_clear_scan_result),
                supportingText = stringResource(R.string.document_discard_description),
                enabled = secondaryActionsEnabled,
                destructive = true,
                onClick = onDiscard,
            )
        }
    }
}

@Composable
private fun SheetContentColumn(title: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.readingContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = PageHarborSpacing.extraLarge),
        ) {
            Text(
                modifier = Modifier
                    .padding(horizontal = PageHarborSpacing.large)
                    .semantics { heading() },
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.size(PageHarborSpacing.small))
            content()
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supportingText: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = Modifier
            .semantics { if (!enabled) disabled() }
            .clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
            )
        },
        headlineContent = { Text(text = title, color = contentColor) },
        supportingContent = {
            Text(
                text = supportingText,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
    )
}

private enum class DocumentSheet { Add, More }

private enum class DocumentEditPanel { None, Tools, Filters }

private data class DocumentBarAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val labelResource: Int,
    val enabled: Boolean,
    val onClick: () -> Unit,
)
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

@Composable
private fun RenameDocumentDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_rename_title)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = title,
                onValueChange = { title = it.take(120) },
                singleLine = true,
                label = { Text(stringResource(R.string.library_document_title_label)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onRename(title) },
            ) {
                Text(stringResource(R.string.library_rename_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun MoveLibraryDocumentDialog(
    folders: List<LibraryFolder>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = { onMove(null) }) {
                    Text(stringResource(R.string.library_root_folder))
                }
                folders.forEach { folder ->
                    TextButton(onClick = { onMove(folder.id) }) {
                        Text(folder.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun DeleteLibraryDocumentDialog(
    title: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_delete_title, title)) },
        text = { Text(stringResource(R.string.document_delete_message)) },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                onClick = onDelete,
            ) {
                Text(stringResource(R.string.library_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
