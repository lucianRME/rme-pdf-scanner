package org.synapseworks.pageharbor.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
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
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme

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
    onRateRme: () -> Unit,
    onSuggestFeature: () -> Unit,
    onShareRme: () -> Unit,
    onAppLock: () -> Unit,
    onMoveFromScanner: () -> Unit,
    onBackupRestore: () -> Unit,
    onMoveToNewPhone: () -> Unit,
    onTopLevelBack: () -> Unit,
    onTopLevelBackSequenceReset: () -> Unit,
    onTransientUiBusyChange: (Boolean) -> Unit = {},
    documentsDestinationRequestId: Long = 0L,
) {
    var namingDialog by remember { mutableStateOf<NamingDialog?>(null) }
    var movingDocument by remember { mutableStateOf<LibraryDocumentSummary?>(null) }
    var deletingDocument by remember { mutableStateOf<LibraryDocumentSummary?>(null) }
    var mergeSelection by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmFolderDelete by remember { mutableStateOf(false) }
    var toolsQuery by rememberSaveable { mutableStateOf("") }
    var destinationIndex by rememberSaveable { mutableIntStateOf(LibraryDestination.Home.ordinal) }
    val destination = LibraryDestination.entries[destinationIndex]
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val selectedFolder = libraryUiState.folders.firstOrNull {
        it.id == libraryUiState.selectedFolderId
    }
    val working = libraryUiState.actionState == LibraryActionState.Working
    val acquiring = scannerSpikeState == ScannerSpikeState.Preparing ||
        importUiState == DocumentImportUiState.Selecting ||
        importUiState is DocumentImportUiState.Processing
    val canStartScan = !working && !acquiring
    val actionMessage = libraryActionMessage(libraryUiState.actionState)
    val actionEventId = when (val state = libraryUiState.actionState) {
        is LibraryActionState.Succeeded -> state.eventId
        is LibraryActionState.Failed -> state.eventId
        LibraryActionState.Idle,
        LibraryActionState.Working,
        -> null
    }

    LaunchedEffect(namingDialog, movingDocument, deletingDocument, confirmFolderDelete) {
        val isBusy =
            namingDialog != null ||
            movingDocument != null ||
            deletingDocument != null ||
            confirmFolderDelete
        onTransientUiBusyChange(isBusy)
        if (isBusy) {
            onTopLevelBackSequenceReset()
        }
    }
    LaunchedEffect(documentsDestinationRequestId) {
        if (documentsDestinationRequestId > 0L) {
            destinationIndex = LibraryDestination.Documents.ordinal
        }
    }
    DisposableEffect(Unit) {
        onDispose { onTransientUiBusyChange(false) }
    }

    BackHandler {
        when {
            destination == LibraryDestination.Tools && toolsQuery.isNotBlank() -> {
                onTopLevelBackSequenceReset()
                toolsQuery = ""
                focusManager.clearFocus(force = true)
            }
            (destination == LibraryDestination.Home || destination == LibraryDestination.Documents) &&
                libraryUiState.query.isNotBlank() -> {
                onTopLevelBackSequenceReset()
                onQueryChange("")
            }
            destination == LibraryDestination.Documents && mergeSelection.isNotEmpty() -> {
                onTopLevelBackSequenceReset()
                mergeSelection = emptyList()
            }
            destination == LibraryDestination.Documents && selectedFolder != null -> {
                onTopLevelBackSequenceReset()
                onFolderSelected(selectedFolder.parentFolderId)
            }
            else -> onTopLevelBack()
        }
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
            LibraryTopAppBar(
                destination = destination,
                onCreateFolder = { namingDialog = NamingDialog.CreateFolder },
            )
        },
        bottomBar = {
            LibraryNavigationBar(
                selectedDestination = destination,
                onDestinationSelected = { selected ->
                    if (selected != destination) onTopLevelBackSequenceReset()
                    destinationIndex = selected.ordinal
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.semantics {
                    if (!canStartScan) disabled()
                },
                containerColor = if (canStartScan) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canStartScan) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = { if (canStartScan) onScanDocument() },
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.home_scan_document),
                )
            }
        },
    ) { padding ->
        when (destination) {
            LibraryDestination.Home -> HomeDestination(
                modifier = Modifier.padding(padding),
                uiState = libraryUiState,
                working = working,
                scannerSpikeState = scannerSpikeState,
                hasActiveSession = hasActiveSession,
                importUiState = importUiState,
                thumbnailUri = thumbnailUri,
                onQueryChange = onQueryChange,
                onImportFiles = onImportFiles,
                onCancelImport = onCancelImport,
                onViewScanResult = onViewScanResult,
                onViewAllDocuments = { destinationIndex = LibraryDestination.Documents.ordinal },
                onOpenDocument = onOpenDocument,
                onRenameDocument = { namingDialog = NamingDialog.RenameDocument(it) },
                onMoveDocument = { movingDocument = it },
                onDeleteDocument = { deletingDocument = it },
                mergeSelection = mergeSelection,
                onToggleMerge = { document ->
                    mergeSelection = mergeSelection.toggle(document.id)
                    destinationIndex = LibraryDestination.Documents.ordinal
                },
            )

            LibraryDestination.Documents -> DocumentsDestination(
                modifier = Modifier.padding(padding),
                uiState = libraryUiState,
                selectedFolder = selectedFolder,
                working = working,
                thumbnailUri = thumbnailUri,
                mergeSelection = mergeSelection,
                onQueryChange = onQueryChange,
                onFolderSelected = onFolderSelected,
                onSortOrderChange = onSortOrderChange,
                onRenameFolder = {
                    selectedFolder?.let { namingDialog = NamingDialog.RenameFolder(it) }
                },
                onDeleteFolder = { confirmFolderDelete = true },
                onClearMerge = { mergeSelection = emptyList() },
                onMerge = { namingDialog = NamingDialog.MergeDocuments },
                onOpenDocument = onOpenDocument,
                onToggleMerge = { document ->
                    mergeSelection = mergeSelection.toggle(document.id)
                },
                onRenameDocument = { namingDialog = NamingDialog.RenameDocument(it) },
                onMoveDocument = { movingDocument = it },
                onDeleteDocument = { deletingDocument = it },
            )

            LibraryDestination.Tools -> ToolsDestination(
                modifier = Modifier.padding(padding),
                interactionEnabled = !working && !acquiring,
                savedDocumentCount = libraryUiState.recentDocuments.size,
                query = toolsQuery,
                onQueryChange = { toolsQuery = it },
                scannerSpikeState = scannerSpikeState,
                hasActiveSession = hasActiveSession,
                importUiState = importUiState,
                onImportFiles = onImportFiles,
                onCancelImport = onCancelImport,
                onViewScanResult = onViewScanResult,
                onOpenDocuments = { message ->
                    destinationIndex = LibraryDestination.Documents.ordinal
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(message)
                    }
                },
            )

            LibraryDestination.More -> MoreDestination(
                modifier = Modifier.padding(padding),
                onRateRme = onRateRme,
                onSuggestFeature = onSuggestFeature,
                onShareRme = onShareRme,
                onAppLock = onAppLock,
                onMoveFromScanner = onMoveFromScanner,
                onBackupRestore = onBackupRestore,
                onMoveToNewPhone = onMoveToNewPhone,
                onPrivacyInfo = onPrivacyInfo,
                onAbout = onAbout,
            )
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

    movingDocument?.let { document ->
        MoveDocumentDialog(
            folders = libraryUiState.folders,
            onDismiss = { movingDocument = null },
            onMove = { folderId ->
                onMoveDocument(document.id, folderId)
                movingDocument = null
            },
        )
    }

    deletingDocument?.let { document ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_named_document_title, document.title),
            message = stringResource(R.string.library_delete_document_message),
            confirmLabel = stringResource(R.string.library_delete_action),
            onDismiss = { deletingDocument = null },
            onConfirm = {
                onDeleteDocument(document.id)
                mergeSelection = mergeSelection - document.id
                deletingDocument = null
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
                val parentFolderId = selectedFolder.parentFolderId
                onDeleteFolder(selectedFolder.id)
                onFolderSelected(parentFolderId)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopAppBar(
    destination: LibraryDestination,
    onCreateFolder: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(destination.titleResource),
            )
        },
        actions = {
            if (destination == LibraryDestination.Documents) {
                IconButton(onClick = onCreateFolder) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(R.string.library_create_folder_action),
                    )
                }
            }
        },
    )
}

@Composable
private fun LibraryNavigationBar(
    selectedDestination: LibraryDestination,
    onDestinationSelected: (LibraryDestination) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.8f
    val navigationLabelStyle = if (largeText) {
        MaterialTheme.typography.labelMedium.copy(fontSize = 7.sp)
    } else {
        MaterialTheme.typography.labelMedium
    }
    NavigationBar {
        LibraryDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selectedDestination,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            LibraryDestination.Home -> Icons.Default.Home
                            LibraryDestination.Documents -> Icons.Default.Description
                            LibraryDestination.Tools -> Icons.Default.Build
                            LibraryDestination.More -> Icons.Default.MoreHoriz
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = stringResource(destination.labelResource),
                        maxLines = 1,
                        style = navigationLabelStyle,
                    )
                },
            )
        }
    }
}

@Composable
private fun HomeDestination(
    modifier: Modifier,
    uiState: LibraryUiState,
    working: Boolean,
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    thumbnailUri: (String?) -> Uri?,
    onQueryChange: (String) -> Unit,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onViewAllDocuments: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onRenameDocument: (LibraryDocumentSummary) -> Unit,
    onMoveDocument: (LibraryDocumentSummary) -> Unit,
    onDeleteDocument: (LibraryDocumentSummary) -> Unit,
    mergeSelection: List<String>,
    onToggleMerge: (LibraryDocumentSummary) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val useGrid = maxWidth >= 600.dp && !(fontScale >= 1.8f && maxWidth < 840.dp)
        val horizontalPadding = when {
            maxWidth >= 840.dp -> PageHarborLayout.expandedScreenHorizontalPadding
            maxWidth >= 600.dp -> PageHarborLayout.mediumScreenHorizontalPadding
            else -> PageHarborLayout.compactScreenHorizontalPadding
        }
        val recentDocuments = if (uiState.recentDocuments.isEmpty() && uiState.query.isBlank()) {
            uiState.documents
        } else {
            uiState.recentDocuments
        }
        val displayedDocuments = if (uiState.query.isBlank()) {
            recentDocuments.take(HOME_RECENT_DOCUMENT_LIMIT)
        } else {
            uiState.documents
        }
        val showActiveStatus = scannerSpikeState == ScannerSpikeState.Preparing ||
            hasActiveSession || importUiState is DocumentImportUiState.Processing

        LazyVerticalGrid(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.expandedContentMaxWidth)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            columns = if (useGrid) {
                GridCells.Adaptive(PageHarborLayout.libraryGridMinimumCellWidth)
            } else {
                GridCells.Fixed(1)
            },
            contentPadding = PaddingValues(
                top = PageHarborSpacing.small,
                bottom = PageHarborLayout.navigationContentBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
        ) {
            item(key = "home-search", span = { GridItemSpan(maxLineSpan) }) {
                LibrarySearchField(
                    modifier = Modifier.fillMaxWidth(),
                    query = uiState.query,
                    onQueryChange = onQueryChange,
                )
            }
            if (showActiveStatus) {
                item(key = "home-active-work", span = { GridItemSpan(maxLineSpan) }) {
                    ActiveWorkStatus(
                        scannerSpikeState = scannerSpikeState,
                        hasActiveSession = hasActiveSession,
                        importUiState = importUiState,
                        onViewScanResult = onViewScanResult,
                        onCancelImport = onCancelImport,
                    )
                }
            }
            if (displayedDocuments.isEmpty()) {
                item(key = "home-empty", span = { GridItemSpan(maxLineSpan) }) {
                    HomeEmptyState(
                        query = uiState.query,
                        enabled = !working && importUiState !is DocumentImportUiState.Processing,
                        onImportFiles = onImportFiles,
                    )
                }
            } else {
                item(key = "home-section-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(
                            if (uiState.query.isBlank()) {
                                R.string.home_recent_documents
                            } else {
                                R.string.home_search_results
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(displayedDocuments, key = { "home-${it.id}" }) { document ->
                    LibraryDocumentItem(
                        document = document,
                        thumbnailUri = thumbnailUri(document.thumbnailRelativePath),
                        expanded = useGrid,
                        mergePosition = mergeSelection.indexOf(document.id).takeIf { it >= 0 },
                        enabled = !working,
                        onOpen = { onOpenDocument(document.id) },
                        onToggleMerge = { onToggleMerge(document) },
                        onRename = { onRenameDocument(document) },
                        onMove = { onMoveDocument(document) },
                        onDelete = { onDeleteDocument(document) },
                    )
                }
                item(key = "home-view-all", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onViewAllDocuments,
                    ) {
                        Text(stringResource(R.string.home_view_all_documents))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyState(
    query: String,
    enabled: Boolean,
    onImportFiles: () -> Unit,
) {
    if (query.isNotBlank()) {
        LibraryEmptyState(query = query, selectedFolderName = null)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(vertical = PageHarborSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.home_library_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier
                .padding(top = PageHarborSpacing.small)
                .widthIn(max = PageHarborLayout.homeContentMaxWidth),
            text = stringResource(R.string.home_library_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            modifier = Modifier.padding(top = PageHarborSpacing.large),
            enabled = enabled,
            onClick = onImportFiles,
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                imageVector = Icons.Default.UploadFile,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.padding(start = PageHarborSpacing.small),
                text = stringResource(R.string.home_import_files),
            )
        }
    }
}

@Composable
private fun DocumentsDestination(
    modifier: Modifier,
    uiState: LibraryUiState,
    selectedFolder: LibraryFolder?,
    working: Boolean,
    thumbnailUri: (String?) -> Uri?,
    mergeSelection: List<String>,
    onQueryChange: (String) -> Unit,
    onFolderSelected: (String?) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onRenameFolder: () -> Unit,
    onDeleteFolder: () -> Unit,
    onClearMerge: () -> Unit,
    onMerge: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onToggleMerge: (LibraryDocumentSummary) -> Unit,
    onRenameDocument: (LibraryDocumentSummary) -> Unit,
    onMoveDocument: (LibraryDocumentSummary) -> Unit,
    onDeleteDocument: (LibraryDocumentSummary) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxWidth < 600.dp
        val stackControls = maxWidth < 380.dp || fontScale >= 1.5f
        val useGrid = !compact && !(fontScale >= 1.8f && maxWidth < 840.dp)
        val horizontalPadding = when {
            maxWidth >= 840.dp -> PageHarborLayout.expandedScreenHorizontalPadding
            maxWidth >= 600.dp -> PageHarborLayout.mediumScreenHorizontalPadding
            else -> PageHarborLayout.compactScreenHorizontalPadding
        }

        LazyVerticalGrid(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.libraryContentMaxWidth)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            columns = if (useGrid) {
                GridCells.Adaptive(PageHarborLayout.libraryGridMinimumCellWidth)
            } else {
                GridCells.Fixed(1)
            },
            contentPadding = PaddingValues(
                top = PageHarborSpacing.small,
                bottom = PageHarborLayout.navigationContentBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
        ) {
            item(key = "documents-search", span = { GridItemSpan(maxLineSpan) }) {
                LibrarySearchField(
                    modifier = Modifier.fillMaxWidth(),
                    query = uiState.query,
                    onQueryChange = onQueryChange,
                )
            }
            item(key = "documents-folders", span = { GridItemSpan(maxLineSpan) }) {
                FolderControls(
                    folders = uiState.folders,
                    breadcrumb = uiState.folderBreadcrumb,
                    selectedFolderId = uiState.selectedFolderId,
                    enabled = !working,
                    onFolderSelected = onFolderSelected,
                )
            }
            item(key = "documents-heading", span = { GridItemSpan(maxLineSpan) }) {
                DocumentSectionHeader(
                    documentCount = uiState.documents.size,
                    sortOrder = uiState.sortOrder,
                    selectedFolder = selectedFolder,
                    working = working,
                    stackControls = stackControls,
                    onSortOrderChange = onSortOrderChange,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                )
            }
            if (mergeSelection.isNotEmpty()) {
                item(key = "documents-merge", span = { GridItemSpan(maxLineSpan) }) {
                    MergeBar(
                        selectedCount = mergeSelection.size,
                        enabled = !working,
                        stackActions = stackControls,
                        onClear = onClearMerge,
                        onMerge = onMerge,
                    )
                }
            }
            if (uiState.documents.isEmpty()) {
                item(key = "documents-empty", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryEmptyState(
                        query = uiState.query,
                        selectedFolderName = selectedFolder?.name,
                    )
                }
            } else {
                items(uiState.documents, key = { "documents-${it.id}" }) { document ->
                    LibraryDocumentItem(
                        document = document,
                        thumbnailUri = thumbnailUri(document.thumbnailRelativePath),
                        expanded = useGrid,
                        mergePosition = mergeSelection.indexOf(document.id).takeIf { it >= 0 },
                        enabled = !working,
                        onOpen = { onOpenDocument(document.id) },
                        onToggleMerge = { onToggleMerge(document) },
                        onRename = { onRenameDocument(document) },
                        onMove = { onMoveDocument(document) },
                        onDelete = { onDeleteDocument(document) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolsDestination(
    modifier: Modifier,
    interactionEnabled: Boolean,
    savedDocumentCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onOpenDocuments: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var imeWasVisible by remember { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val mergeGuidance = stringResource(R.string.tools_merge_guidance)
    val pagesGuidance = stringResource(R.string.tools_pages_guidance)
    val ocrGuidance = stringResource(R.string.tools_ocr_guidance)
    val mergeEnabled = interactionEnabled && savedDocumentCount >= 2
    val documentToolEnabled = interactionEnabled && savedDocumentCount >= 1
    val tools = listOf(
        ToolLauncherItemModel(
            key = "import",
            icon = Icons.Default.UploadFile,
            label = stringResource(R.string.tools_import_title),
            accessibilityLabel = if (interactionEnabled) {
                stringResource(R.string.tools_import_accessibility)
            } else {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_import_title),
                )
            },
            searchTerms = listOf("import", "file", "image", "pdf"),
            enabled = interactionEnabled,
            onClick = onImportFiles,
        ),
        ToolLauncherItemModel(
            key = "merge",
            icon = Icons.AutoMirrored.Filled.CallMerge,
            label = stringResource(R.string.tools_merge_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_merge_title),
                )
            } else if (mergeEnabled) {
                stringResource(R.string.tools_merge_accessibility)
            } else {
                stringResource(R.string.tools_merge_unavailable_accessibility)
            },
            searchTerms = listOf("merge", "combine", "join"),
            enabled = mergeEnabled,
            onClick = { onOpenDocuments(mergeGuidance) },
        ),
        ToolLauncherItemModel(
            key = "pages",
            icon = Icons.Default.ContentCut,
            label = stringResource(R.string.tools_pages_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_pages_title),
                )
            } else if (documentToolEnabled) {
                stringResource(R.string.tools_pages_accessibility)
            } else {
                stringResource(R.string.tools_pages_unavailable_accessibility)
            },
            searchTerms = listOf("split", "extract", "pages"),
            enabled = documentToolEnabled,
            onClick = { onOpenDocuments(pagesGuidance) },
        ),
        ToolLauncherItemModel(
            key = "ocr",
            icon = Icons.Default.TextFields,
            label = stringResource(R.string.tools_ocr_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_ocr_title),
                )
            } else if (documentToolEnabled) {
                stringResource(R.string.tools_ocr_accessibility)
            } else {
                stringResource(R.string.tools_ocr_unavailable_accessibility)
            },
            searchTerms = listOf("ocr", "text", "recognize", "extract text"),
            enabled = documentToolEnabled,
            onClick = { onOpenDocuments(ocrGuidance) },
        ),
    )
    val normalizedQuery = query.trim()
    LaunchedEffect(imeBottom, query) {
        val imeVisible = imeBottom > 0
        if (imeWasVisible && !imeVisible && searchFieldFocused && query.isNotBlank()) {
            onQueryChange("")
            focusManager.clearFocus(force = true)
        }
        imeWasVisible = imeVisible
    }
    val visibleTools = if (normalizedQuery.isEmpty()) {
        tools
    } else {
        tools.filter { tool ->
            (tool.searchTerms + tool.label).any { term ->
                term.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val columnCount = when {
            fontScale >= 1.8f && maxWidth >= 840.dp -> 4
            fontScale >= 1.8f && maxWidth >= 600.dp -> 3
            fontScale >= 1.8f -> 2
            maxWidth < 360.dp -> 3
            else -> 4
        }
        val horizontalPadding = when {
            maxWidth >= 840.dp -> PageHarborLayout.expandedScreenHorizontalPadding
            maxWidth >= 600.dp -> PageHarborLayout.mediumScreenHorizontalPadding
            else -> PageHarborLayout.compactScreenHorizontalPadding
        }
        val showActiveStatus = scannerSpikeState == ScannerSpikeState.Preparing ||
            hasActiveSession || importUiState is DocumentImportUiState.Processing

        LazyVerticalGrid(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.expandedContentMaxWidth)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            columns = GridCells.Fixed(columnCount),
            contentPadding = PaddingValues(
                top = PageHarborSpacing.small,
                bottom = PageHarborLayout.navigationContentBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            item(key = "tools-search", span = { GridItemSpan(maxLineSpan) }) {
                ToolsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { searchFieldFocused = it.isFocused },
                )
            }
            if (showActiveStatus) {
                item(key = "tools-active-work", span = { GridItemSpan(maxLineSpan) }) {
                    ActiveWorkStatus(
                        scannerSpikeState = scannerSpikeState,
                        hasActiveSession = hasActiveSession,
                        importUiState = importUiState,
                        onViewScanResult = onViewScanResult,
                        onCancelImport = onCancelImport,
                    )
                }
            }
            if (visibleTools.isEmpty()) {
                item(key = "tools-empty", span = { GridItemSpan(maxLineSpan) }) {
                    ToolsEmptyState()
                }
            } else {
                item(key = "tools-heading-all", span = { GridItemSpan(maxLineSpan) }) {
                    ToolSectionHeading(R.string.tools_all_heading)
                }
                items(visibleTools, key = { "tools-${it.key}" }) { tool ->
                    ToolLauncherItem(tool)
                }
            }
        }
    }
}

@Composable
private fun MoreDestination(
    modifier: Modifier,
    onRateRme: () -> Unit,
    onSuggestFeature: () -> Unit,
    onShareRme: () -> Unit,
    onAppLock: () -> Unit,
    onMoveFromScanner: () -> Unit,
    onBackupRestore: () -> Unit,
    onMoveToNewPhone: () -> Unit,
    onPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.homeContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PageHarborLayout.compactScreenHorizontalPadding,
                    top = PageHarborSpacing.large,
                    end = PageHarborLayout.compactScreenHorizontalPadding,
                    bottom = PageHarborLayout.navigationContentBottomPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall)) {
                Text(
                    modifier = Modifier.semantics { heading() },
                    text = stringResource(R.string.app_name_short),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.more_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.more_data_backup_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            MoreActionGroup(
                actions = listOf(
                    MoreAction(
                        Icons.Default.MoveToInbox,
                        R.string.more_move_scanner_title,
                        R.string.more_move_scanner_description,
                        onMoveFromScanner,
                    ),
                    MoreAction(
                        Icons.Default.Backup,
                        R.string.more_backup_restore_title,
                        R.string.more_backup_restore_description,
                        onBackupRestore,
                    ),
                    MoreAction(
                        Icons.Default.PhoneAndroid,
                        R.string.more_new_phone_title,
                        R.string.more_new_phone_description,
                        onMoveToNewPhone,
                    ),
                ),
            )
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.more_security_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            MoreActionGroup(
                actions = listOf(
                    MoreAction(
                        Icons.Default.Lock,
                        R.string.more_app_lock_title,
                        R.string.more_app_lock_description,
                        onAppLock,
                    ),
                ),
            )
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.more_rme_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            MoreActionGroup(
                actions = listOf(
                    MoreAction(
                        Icons.Default.Lightbulb,
                        R.string.more_suggest_title,
                        R.string.more_suggest_description,
                        onSuggestFeature,
                    ),
                    MoreAction(
                        Icons.Default.Star,
                        R.string.more_rate_title,
                        R.string.more_rate_description,
                        onRateRme,
                    ),
                    MoreAction(
                        Icons.Default.Share,
                        R.string.more_share_title,
                        R.string.more_share_description,
                        onShareRme,
                    ),
                    MoreAction(
                        Icons.Default.PrivacyTip,
                        R.string.home_privacy_action,
                        R.string.more_privacy_description,
                        onPrivacyInfo,
                    ),
                    MoreAction(
                        Icons.Default.Info,
                        R.string.home_about_action,
                        R.string.more_about_description,
                        onAbout,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun MoreActionGroup(actions: List<MoreAction>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            actions.forEachIndexed { index, action ->
                ListItem(
                    modifier = Modifier.clickable(onClick = action.onClick),
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text(stringResource(action.titleResource)) },
                    supportingContent = { Text(stringResource(action.descriptionResource)) },
                )
                if (index < actions.lastIndex) HorizontalDivider()
            }
        }
    }
}

private data class MoreAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val titleResource: Int,
    val descriptionResource: Int,
    val onClick: () -> Unit,
)

@Composable
private fun ToolSectionHeading(titleResource: Int) {
    Text(
        modifier = Modifier
            .padding(top = PageHarborSpacing.extraSmall)
            .semantics { heading() },
        text = stringResource(titleResource),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ToolsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tools_search_label)
    OutlinedTextField(
        modifier = modifier.semantics { contentDescription = label },
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        placeholder = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.tools_search_clear),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ToolLauncherItem(tool: ToolLauncherItemModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clickable(
                enabled = tool.enabled,
                role = Role.Button,
                onClick = tool.onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = tool.accessibilityLabel
                if (!tool.enabled) disabled()
            }
            .padding(
                horizontal = PageHarborSpacing.extraSmall,
                vertical = PageHarborSpacing.small,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.large,
            color = if (tool.enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    modifier = Modifier.size(28.dp),
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = if (tool.enabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
        Text(
            text = tool.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (tool.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun ToolsEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PageHarborSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.tools_no_results),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class ToolLauncherItemModel(
    val key: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val accessibilityLabel: String,
    val searchTerms: List<String>,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

private enum class LibraryDestination(
    val titleResource: Int,
    val labelResource: Int,
) {
    Home(R.string.app_name_short, R.string.navigation_home),
    Documents(R.string.navigation_documents, R.string.navigation_documents),
    Tools(R.string.navigation_tools, R.string.navigation_tools),
    More(R.string.navigation_more, R.string.navigation_more),
}

private fun List<String>.toggle(value: String): List<String> =
    if (value in this) this - value else this + value

private const val HOME_RECENT_DOCUMENT_LIMIT = 4

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier,
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        label = { Text(stringResource(R.string.library_search_label)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.library_search_clear),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
    )
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
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize))
                Text(
                    modifier = Modifier.weight(1f),
                    text = if (importUiState.preparedPages > 0) {
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
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCancelImport) {
                    Text(stringResource(R.string.import_cancel_action))
                }
            }
        }
        scannerSpikeState == ScannerSpikeState.Preparing -> Row(
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize))
            Text(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                text = stringResource(R.string.home_scan_preparing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hasActiveSession -> FilledTonalButton(onClick = onViewScanResult) {
            Text(stringResource(R.string.home_view_scan_result))
        }
    }
}

@Composable
private fun FolderControls(
    folders: List<LibraryFolder>,
    breadcrumb: List<LibraryFolder>,
    selectedFolderId: String?,
    enabled: Boolean,
    onFolderSelected: (String?) -> Unit,
) {
    val childFolders = folders.filter { it.parentFolderId == selectedFolderId }
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = enabled,
                onClick = { onFolderSelected(null) },
            ) {
                Text(stringResource(R.string.library_all_documents))
            }
            breadcrumb.forEach { folder ->
                Text(
                    text = ">",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = enabled && folder.id != selectedFolderId,
                    onClick = { onFolderSelected(folder.id) },
                ) {
                    Text(
                        text = folder.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (childFolders.isNotEmpty()) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.library_child_folders_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                childFolders.forEach { folder ->
                    FilterChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { onFolderSelected(folder.id) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.library_folder_with_count,
                                    folder.name,
                                    folder.documentCount,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentSectionHeader(
    documentCount: Int,
    sortOrder: LibrarySortOrder,
    selectedFolder: LibraryFolder?,
    working: Boolean,
    stackControls: Boolean,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onRenameFolder: () -> Unit,
    onDeleteFolder: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showFolderMenu by remember { mutableStateOf(false) }
    val sortLabel = stringResource(sortOrder.labelResource)
    val headingContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = selectedFolder?.name ?: stringResource(R.string.library_documents_heading),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (documentCount == 1) {
                    stringResource(R.string.library_document_count_single)
                } else {
                    stringResource(R.string.library_documents_count, documentCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val controls: @Composable () -> Unit = {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = PageHarborSpacing.small)
                    .size(PageHarborLayout.inlineProgressIndicatorSize),
            )
        }
        SortMenuButton(
            sortLabel = sortLabel,
            enabled = !working,
            expanded = showSortMenu,
            onExpandedChange = { showSortMenu = it },
            onSortOrderChange = onSortOrderChange,
        )
        if (selectedFolder != null) {
            FolderMenuButton(
                folder = selectedFolder,
                enabled = !working,
                expanded = showFolderMenu,
                onExpandedChange = { showFolderMenu = it },
                onRename = onRenameFolder,
                onDelete = onDeleteFolder,
            )
        }
    }
    if (stackControls) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            headingContent(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) { controls() }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            headingContent(Modifier.weight(1f))
            controls()
        }
    }
}

@Composable
private fun SortMenuButton(
    sortLabel: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
) {
    Box {
        TextButton(enabled = enabled, onClick = { onExpandedChange(true) }) {
            Text(stringResource(R.string.library_sort_action, sortLabel))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            LibrarySortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.labelResource)) },
                    onClick = {
                        onSortOrderChange(order)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderMenuButton(
    folder: LibraryFolder,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(enabled = enabled, onClick = { onExpandedChange(true) }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.library_folder_actions, folder.name),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_folder_rename)) },
                onClick = {
                    onExpandedChange(false)
                    onRename()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.library_folder_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun MergeBar(
    selectedCount: Int,
    enabled: Boolean,
    stackActions: Boolean,
    onClear: () -> Unit,
    onMerge: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        if (stackActions) {
            Column(
                modifier = Modifier.padding(PageHarborSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                Text(
                    text = stringResource(R.string.library_merge_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.library_clear_selection))
                    }
                    Button(enabled = enabled && selectedCount >= 2, onClick = onMerge) {
                        Text(stringResource(R.string.library_merge_action))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(PageHarborSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.library_merge_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.library_clear_selection))
                }
                Button(enabled = enabled && selectedCount >= 2, onClick = onMerge) {
                    Text(stringResource(R.string.library_merge_action))
                }
            }
        }
    }
}

@Composable
private fun LibraryDocumentItem(
    document: LibraryDocumentSummary,
    thumbnailUri: Uri?,
    expanded: Boolean,
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
        enabled = enabled,
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (mergePosition == null) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        if (expanded) {
            Column {
                LibraryThumbnail(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PageHarborLayout.expandedThumbnailHeight),
                    uri = thumbnailUri,
                    title = document.title,
                )
                DocumentDetails(
                    modifier = Modifier.padding(PageHarborSpacing.medium),
                    document = document,
                    formattedDate = formattedDate,
                    mergePosition = mergePosition,
                    enabled = enabled,
                    onToggleMerge = onToggleMerge,
                    onRename = onRename,
                    onMove = onMove,
                    onDelete = onDelete,
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(PageHarborSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                verticalAlignment = Alignment.Top,
            ) {
                LibraryThumbnail(
                    modifier = Modifier
                        .width(PageHarborLayout.compactThumbnailWidth)
                        .height(PageHarborLayout.compactThumbnailHeight),
                    uri = thumbnailUri,
                    title = document.title,
                )
                DocumentDetails(
                    modifier = Modifier.weight(1f),
                    document = document,
                    formattedDate = formattedDate,
                    mergePosition = mergePosition,
                    enabled = enabled,
                    onToggleMerge = onToggleMerge,
                    onRename = onRename,
                    onMove = onMove,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun DocumentDetails(
    document: LibraryDocumentSummary,
    formattedDate: String,
    mergePosition: Int?,
    enabled: Boolean,
    onToggleMerge: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
    ) {
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
                text = folder,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mergePosition != null) {
                Text(
                    text = stringResource(R.string.library_remove_from_merge, mergePosition + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            DocumentOverflowMenu(
                documentTitle = document.title,
                mergePosition = mergePosition,
                enabled = enabled,
                onToggleMerge = onToggleMerge,
                onRename = onRename,
                onMove = onMove,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun DocumentOverflowMenu(
    documentTitle: String,
    mergePosition: Int?,
    enabled: Boolean,
    onToggleMerge: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = enabled, onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.library_more_actions, documentTitle),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (mergePosition == null) {
                            stringResource(R.string.library_add_to_merge)
                        } else {
                            stringResource(R.string.library_remove_from_merge, mergePosition + 1)
                        },
                    )
                },
                onClick = {
                    expanded = false
                    onToggleMerge()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_rename_action)) },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_move_action)) },
                onClick = {
                    expanded = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.library_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun LibraryThumbnail(modifier: Modifier, uri: Uri?, title: String) {
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
        modifier = modifier,
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
                ManagedDocumentPreviewState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(PageHarborLayout.inlineProgressIndicatorSize),
                )
                ManagedDocumentPreviewState.Unavailable -> Text(
                    text = title.trim().firstOrNull()?.uppercase() ?: "R",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    query: String,
    selectedFolderName: String?,
) {
    val title = when {
        query.isNotBlank() -> stringResource(R.string.library_no_results_title)
        selectedFolderName != null -> stringResource(R.string.library_empty_folder_title)
        else -> stringResource(R.string.library_empty_title)
    }
    val message = when {
        query.isNotBlank() -> stringResource(R.string.library_no_results_message)
        selectedFolderName != null -> stringResource(R.string.library_empty_folder_message)
        else -> stringResource(R.string.library_empty_message)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(vertical = PageHarborSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier
                .padding(top = PageHarborSpacing.small)
                .widthIn(max = PageHarborLayout.homeContentMaxWidth),
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
                modifier = Modifier.fillMaxWidth(),
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
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
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
                TextButton(onClick = { onMove(null) }) {
                    Text(stringResource(R.string.library_root_folder))
                }
                folders.forEach { folder ->
                    TextButton(onClick = { onMove(folder.id) }) { Text(folder.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
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
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
    )
}

private val LibrarySortOrder.labelResource: Int
    get() = when (this) {
        LibrarySortOrder.MODIFIED_DESC -> R.string.library_sort_updated
        LibrarySortOrder.CREATED_DESC -> R.string.library_sort_created
        LibrarySortOrder.TITLE_ASC -> R.string.library_sort_title
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
            LibraryActionSuccess.FOLDER_MOVED -> R.string.library_moved
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

@Preview(name = "Library phone", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(
    name = "Library tablet dark",
    widthDp = 900,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun LibraryScreenPreview() {
    val previewState = LibraryUiState(
        documents = listOf(
            LibraryDocumentSummary(
                id = "one",
                title = "Electricity bill",
                createdAtMillis = 1_700_000_000_000,
                modifiedAtMillis = 1_700_000_000_000,
                pageCount = 2,
                folderId = "receipts",
                folderName = "Receipts",
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.INDEXED,
            ),
            LibraryDocumentSummary(
                id = "two",
                title = "Project notes and planning",
                createdAtMillis = 1_690_000_000_000,
                modifiedAtMillis = 1_695_000_000_000,
                pageCount = 5,
                folderId = null,
                folderName = null,
                thumbnailRelativePath = null,
                ocrStatus = LibraryOcrStatus.NOT_INDEXED,
            ),
        ),
        folders = listOf(LibraryFolder("receipts", "Receipts", 1)),
    )
    PageHarborTheme {
        LibraryHomeScreen(
            snackbarHostState = remember { SnackbarHostState() },
            libraryUiState = previewState,
            scannerSpikeState = ScannerSpikeState.Idle,
            hasActiveSession = false,
            importUiState = DocumentImportUiState.Idle,
            showBuildDetails = false,
            buildTypeLabel = "release",
            versionName = "1.5.0",
            versionCode = 16,
            gitRevision = "preview",
            showPrivacyInfo = false,
            showAbout = false,
            thumbnailUri = { null },
            onQueryChange = {},
            onFolderSelected = {},
            onSortOrderChange = {},
            onOpenDocument = {},
            onRenameDocument = { _, _ -> },
            onMoveDocument = { _, _ -> },
            onDeleteDocument = {},
            onMergeDocuments = { _, _ -> },
            onCreateFolder = {},
            onRenameFolder = { _, _ -> },
            onDeleteFolder = {},
            onConsumeLibraryAction = {},
            onScanDocument = {},
            onImportFiles = {},
            onCancelImport = {},
            onViewScanResult = {},
            onPrivacyInfo = {},
            onDismissPrivacyInfo = {},
            onAbout = {},
            onDismissAbout = {},
            onViewSourceCode = {},
            onRateRme = {},
            onSuggestFeature = {},
            onShareRme = {},
            onAppLock = {},
            onMoveFromScanner = {},
            onBackupRestore = {},
            onMoveToNewPhone = {},
            onTopLevelBack = {},
            onTopLevelBackSequenceReset = {},
        )
    }
}
