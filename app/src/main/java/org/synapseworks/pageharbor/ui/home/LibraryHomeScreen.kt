package org.synapseworks.pageharbor.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.library.LibraryActionSuccess
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryError
import org.synapseworks.pageharbor.library.LibraryFolder
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibrarySearchMatch
import org.synapseworks.pageharbor.library.LibrarySortOrder
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

/** Adaptive, local-first entry point for saved and in-progress documents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(
    snackbarHostState: SnackbarHostState,
    libraryUiState: LibraryUiState,
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
    thumbnailUri: (String?) -> Uri?,
    onQueryChange: (String) -> Unit,
    onFolderSelected: (String?) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onOpenDocument: (String) -> Unit,
    onRenameDocument: (String, String) -> Unit,
    onMoveDocument: (String, String?) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onMergeDocuments: (List<String>, String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onConsumeLibraryAction: () -> Unit,
    onScanDocument: () -> Unit,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onPrivacyInfo: () -> Unit,
    onDismissPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
    onDismissAbout: () -> Unit,
    onViewSourceCode: () -> Unit,
) {
    var namingDialog by remember { mutableStateOf<NamingDialog?>(null) }
    var movingDocumentId by remember { mutableStateOf<String?>(null) }
    var deletingDocumentId by remember { mutableStateOf<String?>(null) }
    var mergeSelection by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmFolderDelete by remember { mutableStateOf(false) }
    val selectedFolder = libraryUiState.folders.firstOrNull {
        it.id == libraryUiState.selectedFolderId
    }
    val working = libraryUiState.actionState == LibraryActionState.Working
    val acquiring = scannerSpikeState == ScannerSpikeState.Preparing ||
        importUiState == DocumentImportUiState.Selecting ||
        importUiState is DocumentImportUiState.Processing
    val actionMessage = libraryActionMessage(libraryUiState.actionState)
    val actionEventId = when (val state = libraryUiState.actionState) {
        is LibraryActionState.Succeeded -> state.eventId
        is LibraryActionState.Failed -> state.eventId
        LibraryActionState.Idle,
        LibraryActionState.Working,
        -> null
    }

    LaunchedEffect(actionEventId) {
        if (actionEventId != null && actionMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(actionMessage)
            onConsumeLibraryAction()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.app_name_short),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = PageHarborLayout.compactScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                Button(enabled = !working && !acquiring, onClick = onScanDocument) {
                    Text(stringResource(R.string.home_scan_document))
                }
                OutlinedButton(enabled = !working && !acquiring, onClick = onImportFiles) {
                    Text(stringResource(R.string.home_import_files))
                }
            }
            ActiveWorkStatus(
                scannerSpikeState = scannerSpikeState,
                hasActiveSession = hasActiveSession,
                importUiState = importUiState,
                onViewScanResult = onViewScanResult,
                onCancelImport = onCancelImport,
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = libraryUiState.query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.library_search_label)) },
                supportingText = { Text(stringResource(R.string.library_search_supporting)) },
            )

            FolderControls(
                folders = libraryUiState.folders,
                selectedFolderId = libraryUiState.selectedFolderId,
                enabled = !working,
                onFolderSelected = onFolderSelected,
                onCreateFolder = { namingDialog = NamingDialog.CreateFolder },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
                ) {
                    SortChip(
                        label = stringResource(R.string.library_sort_updated),
                        selected = libraryUiState.sortOrder == LibrarySortOrder.MODIFIED_DESC,
                        enabled = !working,
                    ) { onSortOrderChange(LibrarySortOrder.MODIFIED_DESC) }
                    SortChip(
                        label = stringResource(R.string.library_sort_created),
                        selected = libraryUiState.sortOrder == LibrarySortOrder.CREATED_DESC,
                        enabled = !working,
                    ) { onSortOrderChange(LibrarySortOrder.CREATED_DESC) }
                    SortChip(
                        label = stringResource(R.string.library_sort_title),
                        selected = libraryUiState.sortOrder == LibrarySortOrder.TITLE_ASC,
                        enabled = !working,
                    ) { onSortOrderChange(LibrarySortOrder.TITLE_ASC) }
                }
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
            }

            selectedFolder?.let { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    TextButton(
                        enabled = !working,
                        onClick = { namingDialog = NamingDialog.RenameFolder(folder) },
                    ) { Text(stringResource(R.string.library_folder_rename)) }
                    TextButton(
                        enabled = !working,
                        onClick = { confirmFolderDelete = true },
                    ) { Text(stringResource(R.string.library_folder_delete)) }
                }
            }

            if (mergeSelection.isNotEmpty()) {
                MergeBar(
                    selectedCount = mergeSelection.size,
                    enabled = !working,
                    onClear = { mergeSelection = emptyList() },
                    onMerge = { namingDialog = NamingDialog.MergeDocuments },
                )
            }

            if (libraryUiState.documents.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LibraryEmptyState(
                        query = libraryUiState.query,
                        modifier = Modifier.weight(1f),
                    )
                    LibraryFooter(
                        showBuildDetails = showBuildDetails,
                        versionName = versionName,
                        versionCode = versionCode,
                        buildTypeLabel = buildTypeLabel,
                        gitRevision = gitRevision,
                        onPrivacyInfo = onPrivacyInfo,
                        onAbout = onAbout,
                    )
                }
            } else {
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                ) {
                    items(libraryUiState.documents, key = LibraryDocumentSummary::id) { document ->
                        LibraryDocumentCard(
                            document = document,
                            thumbnailUri = thumbnailUri(document.thumbnailRelativePath),
                            mergePosition = mergeSelection.indexOf(document.id).takeIf { it >= 0 },
                            enabled = !working,
                            onOpen = { onOpenDocument(document.id) },
                            onToggleMerge = {
                                mergeSelection = if (document.id in mergeSelection) {
                                    mergeSelection - document.id
                                } else {
                                    mergeSelection + document.id
                                }
                            },
                            onRename = { namingDialog = NamingDialog.RenameDocument(document) },
                            onMove = { movingDocumentId = document.id },
                            onDelete = { deletingDocumentId = document.id },
                        )
                    }
                    item(key = "library-footer") {
                        LibraryFooter(
                            showBuildDetails = showBuildDetails,
                            versionName = versionName,
                            versionCode = versionCode,
                            buildTypeLabel = buildTypeLabel,
                            gitRevision = gitRevision,
                            onPrivacyInfo = onPrivacyInfo,
                            onAbout = onAbout,
                        )
                    }
                }
            }
        }
    }

    namingDialog?.let { dialog ->
        val initialValue = when (dialog) {
            NamingDialog.CreateFolder,
            NamingDialog.MergeDocuments,
            -> ""
            is NamingDialog.RenameDocument -> dialog.document.title
            is NamingDialog.RenameFolder -> dialog.folder.name
        }
        NameDialog(
            title = stringResource(dialog.titleResource),
            initialValue = initialValue,
            confirmLabel = stringResource(dialog.confirmResource),
            onDismiss = { namingDialog = null },
            onConfirm = { value ->
                when (dialog) {
                    NamingDialog.CreateFolder -> onCreateFolder(value)
                    NamingDialog.MergeDocuments -> onMergeDocuments(mergeSelection, value)
                    is NamingDialog.RenameDocument -> onRenameDocument(dialog.document.id, value)
                    is NamingDialog.RenameFolder -> onRenameFolder(dialog.folder.id, value)
                }
                if (dialog == NamingDialog.MergeDocuments) mergeSelection = emptyList()
                namingDialog = null
            },
        )
    }

    movingDocumentId?.let { documentId ->
        MoveDocumentDialog(
            folders = libraryUiState.folders,
            onDismiss = { movingDocumentId = null },
            onMove = { folderId ->
                onMoveDocument(documentId, folderId)
                movingDocumentId = null
            },
        )
    }

    deletingDocumentId?.let { documentId ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_document_title),
            message = stringResource(R.string.library_delete_document_message),
            confirmLabel = stringResource(R.string.library_delete_action),
            onDismiss = { deletingDocumentId = null },
            onConfirm = {
                onDeleteDocument(documentId)
                mergeSelection = mergeSelection - documentId
                deletingDocumentId = null
            },
        )
    }

    if (confirmFolderDelete && selectedFolder != null) {
        ConfirmDialog(
            title = stringResource(R.string.library_delete_folder_title),
            message = stringResource(R.string.library_delete_folder_message),
            confirmLabel = stringResource(R.string.library_folder_delete),
            onDismiss = { confirmFolderDelete = false },
            onConfirm = {
                onDeleteFolder(selectedFolder.id)
                onFolderSelected(null)
                confirmFolderDelete = false
            },
        )
    }

    if (showPrivacyInfo) PrivacyInfoDialog(onDismissPrivacyInfo)
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
private fun ActiveWorkStatus(
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    onViewScanResult: () -> Unit,
    onCancelImport: () -> Unit,
) {
    when {
        importUiState is DocumentImportUiState.Processing -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier
                    .padding(PageHarborSpacing.medium)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (importUiState.preparedPages > 0) {
                        stringResource(
                            R.string.import_progress_with_pages,
                            importUiState.completedItems,
                            importUiState.totalItems,
                            importUiState.preparedPages,
                        )
                    } else {
                        stringResource(
                            R.string.import_progress,
                            importUiState.completedItems,
                            importUiState.totalItems,
                        )
                    },
                )
                TextButton(onClick = onCancelImport) { Text(stringResource(R.string.import_cancel_action)) }
            }
        }
        scannerSpikeState == ScannerSpikeState.Preparing -> Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(R.string.home_scan_preparing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hasActiveSession -> OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onViewScanResult,
        ) { Text(stringResource(R.string.home_view_scan_result)) }
    }
}

@Composable
private fun FolderControls(
    folders: List<LibraryFolder>,
    selectedFolderId: String?,
    enabled: Boolean,
    onFolderSelected: (String?) -> Unit,
    onCreateFolder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        FilterChip(
            selected = selectedFolderId == null,
            enabled = enabled,
            onClick = { onFolderSelected(null) },
            label = { Text(stringResource(R.string.library_all_documents)) },
        )
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolderId == folder.id,
                enabled = enabled,
                onClick = { onFolderSelected(folder.id) },
                label = { Text(stringResource(R.string.library_folder_with_count, folder.name, folder.documentCount)) },
            )
        }
        FilterChip(
            selected = false,
            enabled = enabled,
            onClick = onCreateFolder,
            label = { Text(stringResource(R.string.library_new_folder)) },
        )
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, enabled = enabled, onClick = onClick, label = { Text(label) })
}

@Composable
private fun MergeBar(
    selectedCount: Int,
    enabled: Boolean,
    onClear: () -> Unit,
    onMerge: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(PageHarborSpacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.library_merge_selected_count, selectedCount))
            Row {
                TextButton(onClick = onClear) { Text(stringResource(R.string.library_clear_selection)) }
                Button(enabled = enabled && selectedCount >= 2, onClick = onMerge) {
                    Text(stringResource(R.string.library_merge_action))
                }
            }
        }
    }
}

@Composable
private fun LibraryDocumentCard(
    document: LibraryDocumentSummary,
    thumbnailUri: Uri?,
    mergePosition: Int?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggleMerge: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val formattedDate = remember(document.modifiedAtMillis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(document.modifiedAtMillis))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            LibraryThumbnail(uri = thumbnailUri, title = document.title)
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.library_document_metadata, document.pageCount, formattedDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.folderName?.let { folder ->
                Text(
                    text = stringResource(R.string.library_document_folder, folder),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = stringResource(document.ocrStatus.labelResource),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.searchMatch?.let { match ->
                Text(
                    text = stringResource(
                        if (match == LibrarySearchMatch.TITLE) {
                            R.string.library_search_match_title
                        } else {
                            R.string.library_search_match_ocr
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            document.searchSnippet?.let { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onOpen) {
                Text(stringResource(R.string.library_open_action))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onToggleMerge,
            ) {
                Text(
                    if (mergePosition == null) {
                        stringResource(R.string.library_add_to_merge)
                    } else {
                        stringResource(R.string.library_remove_from_merge, mergePosition + 1)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(enabled = enabled, onClick = onRename) { Text(stringResource(R.string.library_rename_action)) }
                TextButton(enabled = enabled, onClick = onMove) { Text(stringResource(R.string.library_move_action)) }
                TextButton(enabled = enabled, onClick = onDelete) { Text(stringResource(R.string.library_delete_action)) }
            }
        }
    }
}

@Composable
private fun LibraryThumbnail(uri: Uri?, title: String) {
    val resolver = LocalContext.current.contentResolver
    val currentUri by rememberUpdatedState(uri)
    val state by rememberManagedDocumentPreview(
        requestKey = uri?.toString(),
        isCurrent = { currentUri == uri },
        decode = {
            uri?.let { value ->
                runCatching {
                    resolver.openInputStream(value)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
        },
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (val preview = state) {
                is ManagedDocumentPreviewState.Ready -> Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = preview.owner.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.library_thumbnail_description, title),
                    contentScale = ContentScale.Fit,
                )
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator()
                ManagedDocumentPreviewState.Unavailable -> Text(stringResource(R.string.scan_preview_unavailable))
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(
                    if (query.isBlank()) R.string.library_empty_title else R.string.library_no_results_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(
                    if (query.isBlank()) R.string.library_empty_message else R.string.library_no_results_message,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryFooter(
    showBuildDetails: Boolean,
    versionName: String,
    versionCode: Int,
    buildTypeLabel: String,
    gitRevision: String,
    onPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onPrivacyInfo) { Text(stringResource(R.string.home_privacy_action)) }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.home_about_action)) }
        Text(
            text = stringResource(R.string.home_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showBuildDetails) {
            Text(
                text = stringResource(
                    R.string.home_debug_build_label,
                    versionName,
                    versionCode,
                    buildTypeLabel,
                    gitRevision,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(120) },
                singleLine = true,
                label = { Text(stringResource(R.string.library_name_label)) },
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@Composable
private fun MoveDocumentDialog(
    folders: List<LibraryFolder>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = { onMove(null) }) { Text(stringResource(R.string.library_root_folder)) }
                folders.forEach { folder ->
                    TextButton(onClick = { onMove(folder.id) }) { Text(folder.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

private sealed interface NamingDialog {
    val titleResource: Int
    val confirmResource: Int

    data object CreateFolder : NamingDialog {
        override val titleResource = R.string.library_create_folder_title
        override val confirmResource = R.string.library_create_action
    }

    data object MergeDocuments : NamingDialog {
        override val titleResource = R.string.library_merge_title
        override val confirmResource = R.string.library_merge_action
    }

    data class RenameDocument(val document: LibraryDocumentSummary) : NamingDialog {
        override val titleResource = R.string.library_rename_document_title
        override val confirmResource = R.string.library_rename_action
    }

    data class RenameFolder(val folder: LibraryFolder) : NamingDialog {
        override val titleResource = R.string.library_rename_folder_title
        override val confirmResource = R.string.library_rename_action
    }
}

private val LibraryOcrStatus.labelResource: Int
    get() = when (this) {
        LibraryOcrStatus.NOT_INDEXED -> R.string.library_ocr_not_indexed
        LibraryOcrStatus.PARTIAL -> R.string.library_ocr_partial
        LibraryOcrStatus.INDEXED -> R.string.library_ocr_indexed
        LibraryOcrStatus.FAILED -> R.string.library_ocr_failed
    }

@Composable
internal fun libraryActionMessage(state: LibraryActionState): String? = when (state) {
    LibraryActionState.Idle,
    LibraryActionState.Working,
    -> null
    is LibraryActionState.Succeeded -> stringResource(
        when (state.action) {
            LibraryActionSuccess.SAVED -> R.string.library_saved
            LibraryActionSuccess.RENAMED -> R.string.library_renamed
            LibraryActionSuccess.MOVED -> R.string.library_moved
            LibraryActionSuccess.DELETED -> R.string.library_deleted
            LibraryActionSuccess.FOLDER_CREATED -> R.string.library_folder_created
            LibraryActionSuccess.FOLDER_RENAMED -> R.string.library_folder_renamed
            LibraryActionSuccess.FOLDER_DELETED -> R.string.library_folder_deleted
            LibraryActionSuccess.MERGED -> R.string.library_merged
            LibraryActionSuccess.EXTRACTED -> R.string.library_extracted
            LibraryActionSuccess.SPLIT -> R.string.library_split
            LibraryActionSuccess.OCR_INDEXED -> R.string.library_ocr_indexed
        },
    )
    is LibraryActionState.Failed -> stringResource(
        when (state.error) {
            LibraryError.EMPTY_DOCUMENT -> R.string.library_error_empty
            LibraryError.PAGE_LIMIT_EXCEEDED -> R.string.import_error_page_limit
            LibraryError.INVALID_SELECTION -> R.string.library_error_selection
            LibraryError.TITLE_REQUIRED -> R.string.library_error_name_required
            LibraryError.DUPLICATE_FOLDER -> R.string.library_error_duplicate_folder
            LibraryError.DOCUMENT_NOT_FOUND -> R.string.library_error_document_missing
            LibraryError.FOLDER_NOT_FOUND -> R.string.library_error_folder_missing
            LibraryError.SOURCE_MISSING -> R.string.library_error_source_missing
            LibraryError.SOURCE_TOO_LARGE -> R.string.document_source_too_large
            LibraryError.STORAGE_UNAVAILABLE -> R.string.library_error_storage
            LibraryError.DATABASE_UNAVAILABLE -> R.string.library_error_database
            LibraryError.CORRUPTED_RECORD -> R.string.library_error_corrupted
            LibraryError.SAVE_REQUIRED -> R.string.library_error_save_required
            LibraryError.OPERATION_INTERRUPTED -> R.string.library_error_interrupted
        },
    )
}
