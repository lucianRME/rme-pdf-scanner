package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import android.net.Uri
import android.content.res.Configuration
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.synapseworks.pageharbor.library.folderNavigationItems
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.synapseworks.pageharbor.image.bitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.office.OfficeImportActivity
import org.synapseworks.pageharbor.resourcepack.ResourcePackActivity
import org.synapseworks.pageharbor.document.importing.DocumentImportUiState
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.library.LibraryActionSuccess
import org.synapseworks.pageharbor.library.LibraryDocumentSummary
import org.synapseworks.pageharbor.library.LibraryError
import org.synapseworks.pageharbor.library.LibraryFolder
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibrarySearchMatch
import org.synapseworks.pageharbor.library.LibrarySearchHit
import org.synapseworks.pageharbor.library.LibrarySearchState
import org.synapseworks.pageharbor.library.LibrarySortOrder
import org.synapseworks.pageharbor.library.LibraryUiState
import org.synapseworks.pageharbor.library.LibraryWarning
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeModalBottomSheet
import org.synapseworks.pageharbor.ui.theme.RmeSearchField
import org.synapseworks.pageharbor.ui.theme.RmeTonalChoice
import org.synapseworks.pageharbor.ui.theme.rmeColors
import org.synapseworks.pageharbor.library.SmartNameUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.theme.PageHarborTheme
import org.synapseworks.pageharbor.ui.flow.DocumentToolOperation
import org.synapseworks.pageharbor.ui.flow.ToolInputCardinality
import org.synapseworks.pageharbor.ui.flow.ToolInvocationContext
import org.synapseworks.pageharbor.ui.flow.ToolInvocationSource

/** Adaptive, local-first entry point for saved and in-progress documents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(
    snackbarHostState: SnackbarHostState,
    libraryUiState: LibraryUiState,
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    hasLibraryDocuments: Boolean = true,
    importUiState: DocumentImportUiState,
    showBuildDetails: Boolean,
    buildTypeLabel: String,
    versionName: String,
    versionCode: Int,
    gitRevision: String,
    showPrivacyInfo: Boolean,
    showAbout: Boolean,
    thumbnailUri: (String?) -> Uri?,
    onOpenQrScanner: () -> Unit = {},
    onOpenBusinessCard: () -> Unit = {},
    onOpenReceipt: () -> Unit = {},
    onOpenTable: () -> Unit = {},
    onOpenForms: () -> Unit = {},
    onOpenText: () -> Unit = {},
    onOpenWorkflows: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    onOpenOrganization: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onFolderSelected: (String?) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onOpenDocument: (String, String?) -> Unit,
    onLoadMoreSearchResults: () -> Unit,
    onRequestSmartName: (String) -> Unit,
    onRenameDocument: (String, String) -> Unit,
    onMoveDocument: (String, String?) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onMergeDocuments: (List<String>, String) -> Unit,
    onRecognizeSelectedDocuments: (List<String>) -> Unit = {},
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onConsumeLibraryAction: () -> Unit,
    onScanDocument: () -> Unit,
    onManualScan: () -> Unit = {},
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onCardCopy: () -> Unit = {},
    onImportDocumentTool: (ToolInvocationContext) -> Unit = {},
    onInvokeDocumentTool: (ToolInvocationContext) -> Unit = {},
    onPrivacyInfo: () -> Unit,
    onDismissPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
    onDismissAbout: () -> Unit,
    onViewSourceCode: () -> Unit,
    onRateRme: () -> Unit,
    onSuggestFeature: () -> Unit,
    onShareRme: () -> Unit,
    onOcrLanguage: () -> Unit = {},
    onAppLock: () -> Unit,
    onMoveFromScanner: () -> Unit,
    onBackupRestore: () -> Unit,
    onMoveToNewPhone: () -> Unit,
    onTopLevelBack: () -> Unit,
    onTopLevelBackSequenceReset: () -> Unit,
    onTransientUiBusyChange: (Boolean) -> Unit = {},
    documentsDestinationRequestId: Long = 0L,
    selectedDestination: LibraryDestination = LibraryDestination.Home,
    onDestinationSelected: (LibraryDestination) -> Unit = {},
) {
    var namingDialog by remember { mutableStateOf<NamingDialog?>(null) }
    var movingDocument by remember { mutableStateOf<LibraryDocumentSummary?>(null) }
    var deletingDocument by remember { mutableStateOf<LibraryDocumentSummary?>(null) }
    var mergeSelection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmFolderDelete by remember { mutableStateOf(false) }
    var toolPickerContext by rememberSaveable {
        mutableStateOf<ToolInvocationContext?>(null)
    }
    val destination = selectedDestination
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

    LaunchedEffect(
        namingDialog,
        movingDocument,
        deletingDocument,
        confirmFolderDelete,
        toolPickerContext,
    ) {
        val isBusy =
            namingDialog != null ||
            movingDocument != null ||
            deletingDocument != null ||
            confirmFolderDelete ||
            toolPickerContext != null
        onTransientUiBusyChange(isBusy)
        if (isBusy) {
            onTopLevelBackSequenceReset()
        }
    }
    LaunchedEffect(documentsDestinationRequestId) {
        if (documentsDestinationRequestId > 0L) {
            onDestinationSelected(LibraryDestination.Documents)
        }
    }
    LaunchedEffect(importUiState) {
        if (importUiState is DocumentImportUiState.Processing) {
            toolPickerContext = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { onTransientUiBusyChange(false) }
    }

    BackHandler(enabled = toolPickerContext == null) {
        when {
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

    val configuration = LocalConfiguration.current
    val constrainedScanLayout = configuration.screenHeightDp < 460 ||
        configuration.screenWidthDp < 400 && LocalDensity.current.fontScale >= 1.3f
    val showImportAction = destination == LibraryDestination.Home || destination == LibraryDestination.Documents
    val showScanAction = destination != LibraryDestination.More
    val scanAction: @Composable () -> Unit = {
        val rmeColors = MaterialTheme.rmeColors
        FloatingActionButton(
            modifier = Modifier
                .size(PageHarborLayout.scanFabSize)
                .testTag("library-scan-fab")
                .semantics {
                    if (!canStartScan) disabled()
                },
            shape = RoundedCornerShape(18.dp),
            containerColor = if (canStartScan) {
                rmeColors.fabBackground
            } else {
                rmeColors.disabledBackground
            },
            contentColor = if (canStartScan) {
                rmeColors.fabForeground
            } else {
                rmeColors.iconDisabled
            },
            onClick = { if (canStartScan) onManualScan() },
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.scan_action),
            )
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LibraryTopAppBar(
                destination = destination,
                onRecycle = onOpenRecycleBin,
                onWorkflows = onOpenWorkflows,
                onOrganization = onOpenOrganization,
                organizationEnabled = !working&&!acquiring,
                onCreateFolder = { namingDialog = NamingDialog.CreateFolder },
            )
        },
        bottomBar = {
            Column {
                if (showImportAction || (showScanAction && constrainedScanLayout)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("library-docked-scan-action")
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        if (showImportAction) {
                            RmeSecondaryButton(onClick = onImportFiles, enabled = canStartScan) {
                                Icon(Icons.Default.UploadFile, contentDescription = null)
                                Spacer(Modifier.size(PageHarborSpacing.small))
                                Text(stringResource(R.string.home_import_action))
                            }
                        }
                        scanAction()
                    }
                }
                LibraryNavigationBar(
                    selectedDestination = destination,
                    onDestinationSelected = { selected ->
                        if (selected != destination) onTopLevelBackSequenceReset()
                        onDestinationSelected(selected)
                    },
                )
            }
        },
        floatingActionButton = {
            if (showScanAction && !showImportAction && !constrainedScanLayout) scanAction()
        },
    ) { padding ->
        when (destination) {
            LibraryDestination.Home -> HomeDestination(
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                uiState = libraryUiState,
                working = working,
                scannerSpikeState = scannerSpikeState,
                hasActiveSession = hasActiveSession,
                importUiState = importUiState,
                thumbnailUri = thumbnailUri,
                onQueryChange = onQueryChange,
                onCancelImport = onCancelImport,
                onViewScanResult = onViewScanResult,
                onViewAllDocuments = { onDestinationSelected(LibraryDestination.Documents) },
                onOpenDocument = onOpenDocument,
                onLoadMoreSearchResults = onLoadMoreSearchResults,
                onRenameDocument = { namingDialog = NamingDialog.RenameDocument(it) },
                onMoveDocument = { movingDocument = it },
                onDeleteDocument = { deletingDocument = it },
                mergeSelection = mergeSelection,
                onToggleMerge = { document ->
                    mergeSelection = mergeSelection.toggle(document.id)
                    onDestinationSelected(LibraryDestination.Documents)
                },
            )

            LibraryDestination.Documents -> DocumentsDestination(
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
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
                onMerge = {
                    if (mergeSelection.size >= 2) {
                        namingDialog = NamingDialog.MergeDocuments(mergeSelection)
                    } else {
                        toolPickerContext = ToolInvocationContext(
                            source = ToolInvocationSource.DOCUMENT_SELECTION,
                            operation = DocumentToolOperation.MERGE,
                            documentIds = mergeSelection,
                        )
                    }
                },
                onRecognizeText = { onRecognizeSelectedDocuments(mergeSelection) },
                onOpenDocument = onOpenDocument,
                onLoadMoreSearchResults = onLoadMoreSearchResults,
                onToggleMerge = { document ->
                    mergeSelection = mergeSelection.toggle(document.id)
                },
                onRenameDocument = { namingDialog = NamingDialog.RenameDocument(it) },
                onMoveDocument = { movingDocument = it },
                onDeleteDocument = { deletingDocument = it },
            )

            LibraryDestination.Tools -> ToolsDestination(
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                interactionEnabled = !working && !acquiring,
                scannerSpikeState = scannerSpikeState,
                hasActiveSession = hasActiveSession,
                importUiState = importUiState,
                onImportFiles = onImportFiles,
                onCancelImport = onCancelImport,
                onViewScanResult = onViewScanResult,
                onCardCopy = onCardCopy,
                onAutomaticScan = onScanDocument,
                onQr = onOpenQrScanner,
                onBusinessCard = onOpenBusinessCard,
                onReceipt = onOpenReceipt,
                onTable = onOpenTable,
                onForms = onOpenForms,
                onText = onOpenText,
                onDocumentTool = { operation ->
                    toolPickerContext = ToolInvocationContext(
                        source = ToolInvocationSource.TOOLS_SCREEN,
                        operation = operation,
                        documentIds = emptyList(),
                    )
                },
            )

            LibraryDestination.More -> MoreDestination(
                resourcePacksEnabled = !working && !acquiring,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
                hasLibraryDocuments = hasLibraryDocuments,
                onRateRme = onRateRme,
                onSuggestFeature = onSuggestFeature,
                onShareRme = onShareRme,
                onOcrLanguage = onOcrLanguage,
                onAppLock = onAppLock,
                onMoveFromScanner = onMoveFromScanner,
                onBackupRestore = onBackupRestore,
                onMoveToNewPhone = onMoveToNewPhone,
                onPrivacyInfo = onPrivacyInfo,
                onAbout = onAbout,
            )
        }
    }

    toolPickerContext?.let { request ->
        ToolDocumentPickerSheet(
            request = request,
            documents = libraryUiState.recentDocuments,
            thumbnailUri = thumbnailUri,
            onDismiss = { toolPickerContext = null },
            onScan = {
                toolPickerContext = null
                onManualScan()
            },
            onImport = {
                onImportDocumentTool(request)
            },
            onConfirm = { documentIds ->
                val invocation = request.copy(documentIds = documentIds).requireReady()
                toolPickerContext = null
                if (invocation.operation == DocumentToolOperation.MERGE) {
                    namingDialog = NamingDialog.MergeDocuments(invocation.documentIds)
                } else {
                    onInvokeDocumentTool(invocation)
                }
            },
        )
    }

    namingDialog?.let { dialog ->
        if (dialog is NamingDialog.RenameDocument) {
            LaunchedEffect(dialog.document.id) { onRequestSmartName(dialog.document.id) }
        }
        val initialValue = when (dialog) {
            NamingDialog.CreateFolder -> ""
            is NamingDialog.MergeDocuments -> ""
            is NamingDialog.RenameDocument -> dialog.document.title
            is NamingDialog.RenameFolder -> dialog.folder.name
        }
        NameDialog(
            title = stringResource(dialog.titleResource),
            initialValue = initialValue,
            suggestion = (dialog as? NamingDialog.RenameDocument)?.let { rename ->
                (libraryUiState.smartNameStates[rename.document.id] as? SmartNameUiState.Available)
                    ?.suggestion
                    ?.name
            },
            confirmLabel = stringResource(dialog.confirmResource),
            dismissLabel = stringResource(
                if (dialog is NamingDialog.RenameDocument) {
                    R.string.smart_name_keep_current
                } else {
                    R.string.library_cancel
                },
            ),
            onDismiss = { namingDialog = null },
            onConfirm = { value ->
                when (dialog) {
                    NamingDialog.CreateFolder -> onCreateFolder(value)
                    is NamingDialog.MergeDocuments -> {
                        onMergeDocuments(dialog.documentIds, value)
                        mergeSelection = emptyList()
                    }
                    is NamingDialog.RenameDocument -> onRenameDocument(dialog.document.id, value)
                    is NamingDialog.RenameFolder -> onRenameFolder(dialog.folder.id, value)
                }
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
        RecycleMoveConfirmation(
            title = stringResource(R.string.recycle_move_title, document.title),
            message = stringResource(R.string.recycle_move_message),
            confirmLabel = stringResource(R.string.recycle_move_action),
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
    onWorkflows: () -> Unit,
    destination: LibraryDestination,
    onRecycle: () -> Unit,
    onOrganization: () -> Unit,
    organizationEnabled: Boolean,
    onCreateFolder: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val compactTitle = destination == LibraryDestination.Home &&
        configuration.screenWidthDp < 400 && LocalDensity.current.fontScale >= 1.3f
    val title = stringResource(destination.titleResource)
    TopAppBar(
        title = {
            Text(
                modifier = Modifier.semantics {
                    heading()
                    if (compactTitle) contentDescription = title
                },
                text = if (compactTitle) stringResource(R.string.home_compact_title) else title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            if(destination==LibraryDestination.Home||destination==LibraryDestination.Documents){
                RmeTextButton(onClick=onWorkflows,modifier=Modifier.testTag("open-workflows")){Text("Presets")}
                RmeIconButton(onClick=onRecycle,enabled=organizationEnabled,modifier=Modifier.testTag("open-recycle")){
                    Icon(Icons.Default.Delete,contentDescription=stringResource(R.string.recycle_title))
                }
                RmeIconButton(onClick=onOrganization,enabled=organizationEnabled,modifier=Modifier.testTag("open-organization")){
                    Icon(Icons.Default.Star,contentDescription=stringResource(R.string.org_title))
                }
            }
            if (destination == LibraryDestination.Documents) {
                RmeIconButton(onClick = onCreateFolder) {
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
    val fontScale = LocalDensity.current.fontScale
    val compactLabels = fontScale >= 1.8f ||
        (fontScale >= 1.35f && LocalConfiguration.current.screenWidthDp < 380)
    val rmeColors = MaterialTheme.rmeColors
    NavigationBar(
        containerColor = rmeColors.navigationBackground,
        tonalElevation = NavigationBarDefaults.Elevation,
    ) {
        LibraryDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelResource)
            val displayLabel = if (compactLabels && destination == LibraryDestination.Documents) {
                stringResource(R.string.navigation_documents_short)
            } else {
                label
            }
            NavigationBarItem(
                modifier = if (displayLabel != label) {
                    Modifier.semantics { contentDescription = label }
                } else {
                    Modifier
                },
                selected = destination == selectedDestination,
                onClick = { onDestinationSelected(destination) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = rmeColors.navigationActive,
                    selectedTextColor = rmeColors.navigationActive,
                    indicatorColor = rmeColors.navigationIndicator,
                    unselectedIconColor = rmeColors.navigationInactive,
                    unselectedTextColor = rmeColors.navigationInactive,
                    disabledIconColor = rmeColors.iconDisabled,
                    disabledTextColor = rmeColors.textDisabled,
                ),
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
                        text = displayLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
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
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onViewAllDocuments: () -> Unit,
    onOpenDocument: (String, String?) -> Unit,
    onLoadMoreSearchResults: () -> Unit,
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
        val displayedDocuments = recentDocuments.take(HOME_RECENT_DOCUMENT_LIMIT)
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
            if (uiState.query.isNotBlank()) {
                librarySearchResults(
                    uiState = uiState,
                    enabled = !working,
                    keyPrefix = "home",
                    onOpenDocument = onOpenDocument,
                    onLoadMore = onLoadMoreSearchResults,
                )
            } else if (displayedDocuments.isEmpty()) {
                item(key = "home-empty", span = { GridItemSpan(maxLineSpan) }) {
                    HomeEmptyState()
                }
            } else {
                item(key = "home-section-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.home_recent_documents),
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
                        onOpen = { onOpenDocument(document.id, null) },
                        onToggleMerge = { onToggleMerge(document) },
                        onRename = { onRenameDocument(document) },
                        onMove = { onMoveDocument(document) },
                        onDelete = { onDeleteDocument(document) },
                    )
                }
                item(key = "home-view-all", span = { GridItemSpan(maxLineSpan) }) {
                    RmeTextButton(
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
private fun HomeEmptyState() {
    val compact = LocalDensity.current.fontScale >= 1.3f || LocalConfiguration.current.screenHeightDp < 460
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 0.dp else 240.dp)
            .padding(vertical = if (compact) PageHarborSpacing.medium else PageHarborSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.home_library_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (!compact) {
            Text(
                modifier = Modifier
                    .padding(top = PageHarborSpacing.small)
                    .widthIn(max = PageHarborLayout.homeContentMaxWidth),
                text = stringResource(R.string.home_library_empty_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
    onOpenOrganization: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onFolderSelected: (String?) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onRenameFolder: () -> Unit,
    onDeleteFolder: () -> Unit,
    onClearMerge: () -> Unit,
    onMerge: () -> Unit,
    onRecognizeText: () -> Unit,
    onOpenDocument: (String, String?) -> Unit,
    onLoadMoreSearchResults: () -> Unit,
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

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.libraryContentMaxWidth)
                .fillMaxSize(),
        ) {
            if (mergeSelection.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = horizontalPadding,
                            top = PageHarborSpacing.small,
                            end = horizontalPadding,
                            bottom = PageHarborSpacing.small,
                        ),
                ) {
                    MergeBar(
                        selectedCount = mergeSelection.size,
                        enabled = !working,
                        stackActions = stackControls,
                        onClear = onClearMerge,
                        onMerge = onMerge,
                        onRecognizeText = onRecognizeText,
                    )
                }
            }
            LazyVerticalGrid(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                if (uiState.query.isNotBlank()) {
                    librarySearchResults(
                        uiState = uiState,
                        enabled = !working,
                        keyPrefix = "documents",
                        onOpenDocument = onOpenDocument,
                        onLoadMore = onLoadMoreSearchResults,
                    )
                } else {
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
                                onOpen = { onOpenDocument(document.id, null) },
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
    }
}

private fun LazyGridScope.librarySearchResults(
    uiState: LibraryUiState,
    enabled: Boolean,
    keyPrefix: String,
    onOpenDocument: (String, String?) -> Unit,
    onLoadMore: () -> Unit,
) {
    val query = uiState.query.trim()
    val currentState = when (val state = uiState.searchState) {
        is LibrarySearchState.Loading -> if (state.query == query) state else {
            LibrarySearchState.Loading(query)
        }
        is LibrarySearchState.Results -> if (state.query == query) state else {
            LibrarySearchState.Loading(query)
        }
        is LibrarySearchState.TooShort -> if (state.query == query) state else {
            LibrarySearchState.Loading(query)
        }
        LibrarySearchState.Idle -> LibrarySearchState.Loading(query)
    }

    item(key = "$keyPrefix-search-heading", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.home_search_results),
            style = MaterialTheme.typography.titleLarge,
        )
    }
    when (currentState) {
        LibrarySearchState.Idle -> Unit
        is LibrarySearchState.Loading -> item(
            key = "$keyPrefix-search-loading",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.library_search_loading))
            }
        }
        is LibrarySearchState.TooShort -> item(
            key = "$keyPrefix-search-short",
            span = { GridItemSpan(maxLineSpan) },
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PageHarborSpacing.large)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                text = stringResource(R.string.library_search_more_characters),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        is LibrarySearchState.Results -> {
            if (currentState.hits.isEmpty()) {
                item(
                    key = "$keyPrefix-search-empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    LibraryEmptyState(query = query, selectedFolderName = null)
                }
            } else {
                val pageMatchCounts = if (currentState.hasMoreResults) {
                    emptyMap()
                } else {
                    currentState.hits
                        .filter { it.matchType == LibrarySearchMatch.OCR }
                        .groupingBy(LibrarySearchHit::documentId)
                        .eachCount()
                }
                items(
                    items = currentState.hits,
                    key = { hit ->
                        "$keyPrefix-search-${hit.matchType}-${hit.documentId}-${hit.pageId.orEmpty()}"
                    },
                ) { hit ->
                    LibrarySearchResultItem(
                        hit = hit,
                        matchingPageCount = pageMatchCounts[hit.documentId] ?: 0,
                        enabled = enabled,
                        onOpen = { onOpenDocument(hit.documentId, hit.pageId) },
                    )
                }
                if (currentState.canLoadMore) {
                    item(
                        key = "$keyPrefix-search-more",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        RmeTextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            enabled = enabled,
                            onClick = onLoadMore,
                        ) {
                            Text(stringResource(R.string.library_search_show_more))
                        }
                    }
                } else if (currentState.hasMoreResults) {
                    item(
                        key = "$keyPrefix-search-limit",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(PageHarborSpacing.medium),
                            text = stringResource(
                                R.string.library_search_limit_reached,
                                currentState.hits.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchResultItem(
    hit: LibrarySearchHit,
    matchingPageCount: Int,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val detail = when (hit.matchType) {
        LibrarySearchMatch.TAG -> stringResource(R.string.org_search_match_tag)
        LibrarySearchMatch.TITLE -> stringResource(R.string.library_search_match_title)
        LibrarySearchMatch.FOLDER -> stringResource(R.string.library_search_match_metadata)
        LibrarySearchMatch.OCR -> {
            val page = stringResource(
                R.string.library_search_page,
                (hit.currentPagePosition ?: 0) + 1,
            )
            if (matchingPageCount > 1) {
                stringResource(R.string.library_search_page_with_count, page, matchingPageCount)
            } else {
                page
            }
        }
    }
    val spokenDescription = stringResource(
        R.string.library_search_result_accessibility,
        hit.documentTitle,
        detail,
        hit.snippet.orEmpty(),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) { contentDescription = spokenDescription },
        enabled = enabled,
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
        ) {
            Text(
                text = hit.documentTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            hit.snippet?.let { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDocumentPickerSheet(
    request: ToolInvocationContext,
    documents: List<LibraryDocumentSummary>,
    thumbnailUri: (String?) -> Uri?,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by rememberSaveable(request.operation) { mutableStateOf("") }
    var selection by rememberSaveable(request.operation, request.source) {
        mutableStateOf(request.documentIds)
    }
    val normalizedQuery = query.trim()
    val visibleDocuments = remember(documents, normalizedQuery) {
        if (normalizedQuery.isEmpty()) documents else documents.filter { document ->
            document.title.contains(normalizedQuery, ignoreCase = true) ||
                document.folderName?.contains(normalizedQuery, ignoreCase = true) == true
        }
    }
    val ready = request.copy(documentIds = selection).hasValidCardinality
    val singleSelect = request.operation.inputCardinality == ToolInputCardinality.EXACTLY_ONE
    RmeModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = PageHarborSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(
                    if (singleSelect) {
                        R.string.tool_picker_single_title
                    } else {
                        R.string.tool_picker_merge_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (documents.isNotEmpty()) {
                RmeSearchField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.tool_picker_search),
                    clearContentDescription = stringResource(R.string.library_search_clear),
                )
            }
            when {
                documents.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = PageHarborSpacing.large),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                    ) {
                        Text(
                            text = stringResource(R.string.tool_picker_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.tool_picker_empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
                            RmePrimaryButton(onClick = onScan) {
                                Text(stringResource(R.string.scan_action))
                            }
                            RmeSecondaryButton(onClick = onImport) {
                                Text(stringResource(R.string.tools_import_title))
                            }
                        }
                    }
                }
                visibleDocuments.isEmpty() -> {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = PageHarborSpacing.extraLarge),
                        text = stringResource(R.string.tool_picker_no_results),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        lazyListItems(visibleDocuments, key = LibraryDocumentSummary::id) { document ->
                            val selected = document.id in selection
                            ListItem(
                                modifier = Modifier
                                    .padding(vertical = PageHarborSpacing.extraSmall)
                                    .semantics {
                                        this.selected = selected
                                    }
                                    .clickable(
                                        role = if (singleSelect) Role.RadioButton else Role.Checkbox,
                                    ) {
                                        selection = if (singleSelect) {
                                            listOf(document.id)
                                        } else {
                                            selection.toggle(document.id)
                                        }
                                    },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (selected) {
                                        MaterialTheme.rmeColors.selectedBackground
                                    } else {
                                        MaterialTheme.rmeColors.surfaceLow
                                    },
                                    headlineColor = if (selected) {
                                        MaterialTheme.rmeColors.selectedForeground
                                    } else MaterialTheme.colorScheme.onSurface,
                                    supportingColor = if (selected) {
                                        MaterialTheme.rmeColors.selectedForeground
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                leadingContent = {
                                    LibraryThumbnail(
                                        modifier = Modifier.size(48.dp),
                                        uri = thumbnailUri(document.thumbnailRelativePath),
                                        title = document.title,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = document.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.library_page_count,
                                            document.pageCount,
                                            document.pageCount,
                                        ),
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = if (selected) {
                                            Icons.Default.CheckCircle
                                        } else {
                                            Icons.Default.RadioButtonUnchecked
                                        },
                                        contentDescription = if (selected) {
                                            stringResource(R.string.tool_picker_selected_count, 1)
                                        } else {
                                            null
                                        },
                                        tint = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            if (!singleSelect && selection.size < 2 && documents.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tool_picker_choose_another),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selection.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tool_picker_selected_count, selection.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (documents.isEmpty()) {
                RmeTextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = onDismiss,
                ) {
                    Text(stringResource(R.string.tool_picker_cancel))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small, Alignment.End),
                ) {
                    RmeTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.tool_picker_cancel))
                    }
                    val actionLabel = stringResource(request.operation.actionResource)
                    val actionAccessibilityLabel = stringResource(
                        R.string.tool_picker_primary_action,
                        stringResource(request.operation.titleResource),
                    )
                    RmePrimaryButton(
                        modifier = Modifier.semantics {
                            contentDescription = actionAccessibilityLabel
                        },
                        enabled = ready,
                        onClick = { onConfirm(selection) },
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

private val DocumentToolOperation.titleResource: Int
    get() = when (this) {
        DocumentToolOperation.EXTRACT_TEXT -> R.string.tools_ocr_title
        DocumentToolOperation.SPLIT_EXTRACT -> R.string.tools_pages_title
        DocumentToolOperation.MERGE -> R.string.tools_merge_title
        DocumentToolOperation.FIT_UPLOAD_LIMIT -> R.string.fit_limit_title
        DocumentToolOperation.SHARE_SAFE_COPY -> R.string.share_safe_title
    }

private val DocumentToolOperation.actionResource: Int
    get() = when (this) {
        DocumentToolOperation.EXTRACT_TEXT -> R.string.tools_ocr_title
        DocumentToolOperation.SPLIT_EXTRACT -> R.string.tools_pages_title
        DocumentToolOperation.MERGE -> R.string.tool_picker_continue
        DocumentToolOperation.FIT_UPLOAD_LIMIT -> R.string.fit_limit_title
        DocumentToolOperation.SHARE_SAFE_COPY -> R.string.share_safe_title
    }

@Composable
private fun ToolsDestination(
    modifier: Modifier,
    interactionEnabled: Boolean,
    scannerSpikeState: ScannerSpikeState,
    hasActiveSession: Boolean,
    importUiState: DocumentImportUiState,
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onViewScanResult: () -> Unit,
    onAutomaticScan: () -> Unit,
    onCardCopy: () -> Unit,
    onQr: () -> Unit,
    onBusinessCard: () -> Unit,
    onReceipt: () -> Unit,
    onTable: () -> Unit,
    onForms: () -> Unit,
    onText: () -> Unit,
    onDocumentTool: (DocumentToolOperation) -> Unit,
) {
    val context = LocalContext.current
    val officeAvailable = BuildConfig.M35_OFFICE_ENABLED && Build.SUPPORTED_ABIS.contains("arm64-v8a")
    val tools = listOf(
        ToolLauncherItemModel(
            key = "import",
            group = ToolGroup.CAPTURE,
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
            group = ToolGroup.PDF,
            icon = Icons.AutoMirrored.Filled.CallMerge,
            label = stringResource(R.string.tools_merge_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_merge_title),
                )
            } else {
                stringResource(R.string.tools_merge_accessibility)
            },
            searchTerms = listOf("merge", "combine", "join"),
            enabled = interactionEnabled,
            onClick = { onDocumentTool(DocumentToolOperation.MERGE) },
        ),
        ToolLauncherItemModel(
            key = "pages",
            group = ToolGroup.PDF,
            icon = Icons.Default.ContentCut,
            label = stringResource(R.string.tools_pages_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_pages_title),
                )
            } else {
                stringResource(R.string.tools_pages_accessibility)
            },
            searchTerms = listOf("split", "extract", "pages"),
            enabled = interactionEnabled,
            onClick = { onDocumentTool(DocumentToolOperation.SPLIT_EXTRACT) },
        ),
        ToolLauncherItemModel(
            key = "share-safe-copy",
            group = ToolGroup.PDF,
            icon = Icons.Default.Description,
            label = stringResource(R.string.share_safe_title),
            accessibilityLabel = stringResource(R.string.share_safe_tool_accessibility),
            searchTerms = listOf("share", "safe", "redact", "watermark", "purpose", "portal", "email"),
            enabled = interactionEnabled,
            onClick = { onDocumentTool(DocumentToolOperation.SHARE_SAFE_COPY) },
        ),
        ToolLauncherItemModel(
            key = "fit-upload-limit",
            group = ToolGroup.PDF,
            icon = Icons.Default.FitScreen,
            label = stringResource(R.string.fit_limit_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.fit_limit_title),
                )
            } else {
                stringResource(R.string.fit_limit_tool_accessibility)
            },
            searchTerms = listOf("fit", "upload", "limit", "smaller", "compress", "pdf"),
            enabled = interactionEnabled,
            onClick = { onDocumentTool(DocumentToolOperation.FIT_UPLOAD_LIMIT) },
        ),
        ToolLauncherItemModel(
            key = "card-copy",
            group = ToolGroup.CAPTURE,
            icon = Icons.Default.CreditCard,
            label = stringResource(R.string.card_copy_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.card_copy_title),
                )
            } else {
                stringResource(R.string.card_copy_tool_accessibility)
            },
            searchTerms = listOf("id", "card", "front", "back", "copy"),
            enabled = interactionEnabled,
            onClick = onCardCopy,
        ),
        ToolLauncherItemModel(
            key = "automatic-scan",
            group = ToolGroup.CAPTURE,
            icon = Icons.Default.CameraAlt,
            label = stringResource(R.string.google_auto_scan),
            availabilityText = stringResource(R.string.google_scan_controls),
            accessibilityLabel = stringResource(R.string.google_scan_accessibility, stringResource(R.string.google_auto_scan), stringResource(R.string.google_scan_controls)),
            searchTerms = listOf("automatic", "google", "document", "scan"),
            enabled = interactionEnabled,
            onClick = onAutomaticScan,
        ),
        ToolLauncherItemModel(
            key = "ocr",
            group = ToolGroup.EXTRACTION,
            icon = Icons.Default.TextFields,
            label = stringResource(R.string.tools_ocr_title),
            accessibilityLabel = if (!interactionEnabled) {
                stringResource(
                    R.string.tools_busy_accessibility,
                    stringResource(R.string.tools_ocr_title),
                )
            } else {
                stringResource(R.string.tools_ocr_accessibility)
            },
            searchTerms = listOf("ocr", "text", "recognize", "extract text"),
            enabled = interactionEnabled,
            onClick = { onDocumentTool(DocumentToolOperation.EXTRACT_TEXT) },
        ),
        ToolLauncherItemModel(
            key = "qr",
            group = ToolGroup.CAPTURE,
            icon = Icons.Default.QrCodeScanner,
            label = stringResource(R.string.qr_title),
            enabled = interactionEnabled,
            onClick = onQr,
            testTag = "open-qr",
        ),
        ToolLauncherItemModel(
            key = "business-card",
            group = ToolGroup.EXTRACTION,
            icon = Icons.Default.ContactPage,
            label = stringResource(R.string.tools_business_card),
            enabled = interactionEnabled,
            onClick = onBusinessCard,
            testTag = "open-business-card",
        ),
        ToolLauncherItemModel(
            key = "receipt",
            group = ToolGroup.EXTRACTION,
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            label = stringResource(R.string.tools_receipt),
            enabled = interactionEnabled,
            onClick = onReceipt,
            testTag = "open-receipt",
        ),
        ToolLauncherItemModel(
            key = "table",
            group = ToolGroup.EXTRACTION,
            icon = Icons.Default.TableChart,
            label = stringResource(R.string.tools_table),
            enabled = interactionEnabled,
            onClick = onTable,
            testTag = "open-table",
        ),
        ToolLauncherItemModel(
            key = "forms",
            group = ToolGroup.PDF,
            icon = Icons.Default.Description,
            label = stringResource(R.string.tools_pdf_form),
            enabled = interactionEnabled,
            onClick = onForms,
            testTag = "open-forms",
        ),
        ToolLauncherItemModel(
            key = "text",
            group = ToolGroup.PDF,
            icon = Icons.Default.EditNote,
            label = stringResource(R.string.tools_pdf_text),
            enabled = interactionEnabled,
            onClick = onText,
            testTag = "open-text",
        ),
        ToolLauncherItemModel(
            key = "office",
            group = ToolGroup.PDF,
            icon = Icons.Default.PictureAsPdf,
            label = stringResource(R.string.tools_office),
            enabled = interactionEnabled,
            onClick = { context.startActivity(Intent(context, OfficeImportActivity::class.java)) },
            testTag = "open-office",
        ).takeIf { officeAvailable },
    ).filterNotNull()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val columnCount = when {
            fontScale >= 1.5f || maxWidth < 345.dp -> 2
            maxWidth >= 840.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 3
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
                .testTag("tools-grid")
                .padding(horizontal = horizontalPadding),
            columns = GridCells.Fixed(columnCount),
            contentPadding = PaddingValues(
                top = PageHarborSpacing.small,
                bottom = PageHarborLayout.navigationContentBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
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
            ToolGroup.entries.forEach { group ->
                val groupedTools = tools.filter { it.group == group }
                if (groupedTools.isNotEmpty()) {
                    item(key = "tools-heading-${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                        ToolSectionHeading(group.titleResource)
                    }
                    items(groupedTools, key = { "tools-${it.key}" }) { tool ->
                        ToolLauncherItem(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreDestination(
    resourcePacksEnabled: Boolean,
    modifier: Modifier,
    hasLibraryDocuments: Boolean,
    onRateRme: () -> Unit,
    onSuggestFeature: () -> Unit,
    onShareRme: () -> Unit,
    onOcrLanguage: () -> Unit,
    onAppLock: () -> Unit,
    onMoveFromScanner: () -> Unit,
    onBackupRestore: () -> Unit,
    onMoveToNewPhone: () -> Unit,
    onPrivacyInfo: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PageHarborLayout.homeContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PageHarborLayout.compactScreenHorizontalPadding,
                    top = PageHarborSpacing.medium,
                    end = PageHarborLayout.compactScreenHorizontalPadding,
                    bottom = PageHarborSpacing.extraLarge,
                ),
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.more_data_backup_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            MoreActionGroup(
                actions = listOf(
                    MoreAction(
                        Icons.Default.Backup,
                        R.string.more_backup_restore_title,
                        R.string.more_backup_restore_description,
                        onBackupRestore,
                    ),
                    MoreAction(
                        Icons.Default.MoveToInbox,
                        R.string.more_move_scanner_title,
                        R.string.more_move_scanner_description,
                        onMoveFromScanner,
                    ),
                    MoreAction(
                        Icons.Default.PhoneAndroid,
                        R.string.more_new_phone_title,
                        if (hasLibraryDocuments) {
                            R.string.more_new_phone_description
                        } else {
                            R.string.more_new_phone_empty_description
                        },
                        onMoveToNewPhone,
                        enabled = hasLibraryDocuments,
                    ),
                ),
            )
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.more_preferences_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            MoreActionGroup(
                actions = listOf(
                    MoreAction(
                        Icons.Default.TextFields,
                        R.string.ocr_language_title,
                        R.string.ocr_language_more_description,
                        onOcrLanguage,
                    ),
                    MoreAction(
                        Icons.Default.Description,
                        R.string.more_resource_packs,
                        R.string.more_resource_packs_description,
                        { context.startActivity(Intent(context, ResourcePackActivity::class.java)) },
                        enabled = resourcePacksEnabled,
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.rmeColors.surfaceLow,
    ) {
        Column {
            actions.forEachIndexed { index, action ->
                val contentColor = if (action.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
                ListItem(
                    modifier = Modifier
                        .semantics { if (!action.enabled) disabled() }
                        .clickable(enabled = action.enabled, onClick = action.onClick),
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = MaterialTheme.rmeColors.surfaceLow,
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = if (action.enabled) {
                                MaterialTheme.rmeColors.iconSecondary
                            } else {
                                contentColor
                            },
                        )
                    },
                    headlineContent = {
                        Text(stringResource(action.titleResource), color = contentColor)
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(action.descriptionResource),
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                if (index < actions.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.rmeColors.divider)
                }
            }
        }
    }
}

private data class MoreAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val titleResource: Int,
    val descriptionResource: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
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
private fun ToolLauncherItem(tool: ToolLauncherItemModel) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rmeColors = MaterialTheme.rmeColors
    val tag = tool.testTag?.let { Modifier.testTag(it) } ?: Modifier
    Surface(
        modifier = tag
            .fillMaxWidth()
            .heightIn(min = PageHarborLayout.toolTileMinimumHeight)
            .clickable(
                enabled = tool.enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = tool.onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = tool.accessibilityLabel ?: tool.label
                if (!tool.enabled) disabled()
            },
        shape = MaterialTheme.shapes.large,
        color = when {
            !tool.enabled -> rmeColors.disabledBackground
            pressed -> rmeColors.surfaceHigh
            else -> rmeColors.surfaceLow
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(PageHarborSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
        ) {
            Icon(
                modifier = Modifier.size(PageHarborLayout.toolIconSize),
                imageVector = tool.icon,
                contentDescription = null,
                tint = if (tool.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    rmeColors.iconDisabled
                },
            )
            Text(
                text = tool.label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    tool.enabled -> MaterialTheme.colorScheme.onSurface
                    tool.availabilityText != null -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> rmeColors.textDisabled
                },
                textAlign = TextAlign.Center,
            )
            if (tool.availabilityText != null) {
                Text(
                    text = tool.availabilityText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class ToolLauncherItemModel(
    val key: String,
    val group: ToolGroup,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val accessibilityLabel: String? = null,
    val searchTerms: List<String> = emptyList(),
    val enabled: Boolean,
    val onClick: () -> Unit,
    val testTag: String? = null,
    val availabilityText: String? = null,
)

private enum class ToolGroup(val titleResource: Int) {
    CAPTURE(R.string.tools_capture_heading),
    EXTRACTION(R.string.tools_extraction_heading),
    PDF(R.string.tools_pdf_heading),
}

enum class LibraryDestination(
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

internal enum class SelectionPrimaryAction {
    EXTRACT_TEXT,
    MERGE,
}

internal fun selectionPrimaryAction(selectedCount: Int): SelectionPrimaryAction {
    require(selectedCount > 0)
    return if (selectedCount == 1) {
        SelectionPrimaryAction.EXTRACT_TEXT
    } else {
        SelectionPrimaryAction.MERGE
    }
}

private const val HOME_RECENT_DOCUMENT_LIMIT = 4

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RmeSearchField(
        modifier = modifier,
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.library_search_label),
        clearContentDescription = stringResource(R.string.library_search_clear),
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
                        pluralStringResource(
                            R.plurals.import_progress_with_pages,
                            importUiState.preparedPages,
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
                RmeTextButton(onClick = onCancelImport) {
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
        hasActiveSession -> RmeSecondaryButton(onClick = onViewScanResult) {
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
    var folderQuery by remember { mutableStateOf("") }
    val folderItems = remember(folders, selectedFolderId, folderQuery) {
        folderNavigationItems(folders, selectedFolderId, folderQuery)
    }
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RmeTextButton(
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
                RmeTextButton(
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
        if (folders.isNotEmpty()) {
            RmeTextField(
                modifier = Modifier.fillMaxWidth(),
                value = folderQuery,
                onValueChange = { folderQuery = it.take(256) },
                label = { Text(stringResource(R.string.library_find_folders)) },
                singleLine = true,
                enabled = enabled,
            )
        }
        if (folderItems.isNotEmpty()) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = stringResource(R.string.library_child_folders_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                lazyListItems(folderItems, key = { it.folder.id }) { item ->
                    val folder = item.folder
                    RmeTonalChoice(
                        selected = false,
                        enabled = enabled,
                        onClick = { onFolderSelected(folder.id) },
                        showCheckmark = false,
                    ) {
                        Text(
                            stringResource(
                                R.string.library_folder_with_count,
                                item.path,
                                folder.documentCount,
                            ),
                        )
                    }
                }
            }
        } else if (folderQuery.isNotBlank()) {
            Text(stringResource(R.string.library_no_matching_folders))
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
        RmeTextButton(enabled = enabled, onClick = { onExpandedChange(true) }) {
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
        RmeIconButton(enabled = enabled, onClick = { onExpandedChange(true) }) {
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
    onRecognizeText: () -> Unit,
) {
    val recognizeLabel = stringResource(R.string.ocr_batch_recognize_selected)
    var showMore by remember { mutableStateOf(false) }
    val compactActions = stackActions
    val primaryAction = selectionPrimaryAction(selectedCount)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = PageHarborSpacing.small),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RmeIconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.library_clear_selection),
                )
            }
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.library_merge_selected_count, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (primaryAction == SelectionPrimaryAction.EXTRACT_TEXT) {
                RmeSecondaryButton(
                    modifier = Modifier.semantics { contentDescription = recognizeLabel },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = PageHarborSpacing.medium),
                    onClick = onRecognizeText,
                ) {
                    Icon(Icons.Default.TextFields, contentDescription = null)
                    if (!compactActions) {
                        Text(
                            modifier = Modifier.padding(start = PageHarborSpacing.extraSmall),
                            text = stringResource(R.string.tools_ocr_title),
                            maxLines = 1,
                        )
                    }
                }
            } else {
                RmePrimaryButton(
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = PageHarborSpacing.medium),
                    onClick = onMerge,
                ) {
                    Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null)
                    if (!compactActions) {
                        Text(
                            modifier = Modifier.padding(start = PageHarborSpacing.extraSmall),
                            text = stringResource(R.string.library_merge_action),
                            maxLines = 1,
                        )
                    }
                }
            }
            Box {
                RmeIconButton(enabled = enabled, onClick = { showMore = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.document_action_more),
                    )
                }
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    if (primaryAction == SelectionPrimaryAction.EXTRACT_TEXT) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_merge_action)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallMerge, null) },
                            onClick = {
                                showMore = false
                                onMerge()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(recognizeLabel) },
                            leadingIcon = { Icon(Icons.Default.TextFields, null) },
                            onClick = {
                                showMore = false
                                onRecognizeText()
                            },
                        )
                    }
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
    val formattedDate = remember(
        document.documentDateEpochDay,
        document.importedAtMillis,
        document.modifiedAtMillis,
    ) {
        document.documentDateEpochDay?.let { epochDay ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(LocalDate.ofEpochDay(epochDay))
        } ?: DateFormat.getDateInstance(DateFormat.MEDIUM).format(
            Date(document.importedAtMillis ?: document.modifiedAtMillis),
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = mergePosition != null },
        enabled = enabled,
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (mergePosition == null) {
                MaterialTheme.rmeColors.surfaceLow
            } else {
                MaterialTheme.rmeColors.selectedBackground
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
    val selected = mergePosition != null
    val primaryContentColor = if (selected) {
        MaterialTheme.rmeColors.selectedForeground
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryContentColor = if (selected) {
        MaterialTheme.rmeColors.selectedForeground
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                color = primaryContentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            RmeIconButton(enabled = enabled, onClick = onToggleMerge) {
                Icon(
                    imageVector = if (mergePosition == null) {
                        Icons.Default.CheckBoxOutlineBlank
                    } else {
                        Icons.Default.CheckBox
                    },
                    contentDescription = if (mergePosition == null) {
                        stringResource(R.string.library_add_to_merge)
                    } else {
                        stringResource(R.string.library_remove_from_merge, mergePosition + 1)
                    },
                    tint = if (mergePosition == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.rmeColors.selectedForeground
                    },
                )
            }
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
        Text(
            text = pluralStringResource(
                when {
                    document.documentDateEpochDay != null -> R.plurals.library_document_date_metadata
                    document.importedAtMillis != null -> R.plurals.library_imported_metadata
                    else -> R.plurals.library_document_metadata
                },
                document.pageCount,
                document.pageCount,
                formattedDate,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = secondaryContentColor,
        )
        document.folderName?.let { folder ->
            Text(
                text = folder,
                style = MaterialTheme.typography.labelMedium,
                color = secondaryContentColor,
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
                color = if (selected) {
                    MaterialTheme.rmeColors.selectedForeground
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        document.searchSnippet?.let { snippet ->
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryContentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
    val selectionAction = if (mergePosition == null) {
        stringResource(R.string.library_add_to_merge)
    } else {
        stringResource(R.string.library_remove_from_merge, mergePosition + 1)
    }
    Box {
        RmeIconButton(enabled = enabled, onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.library_more_actions, documentTitle),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                modifier = Modifier.semantics { contentDescription = selectionAction },
                text = {
                    Text(selectionAction)
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
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val target = bitmapDisplaySize(viewport.width, viewport.height)
    val state by rememberManagedDocumentPreview(
        requestKey = uri?.toString() to target,
        isCurrent = { currentUri == uri },
        decode = {
            if (uri != null && target != null) decodeDisplayBitmap({ resolver.openInputStream(uri) }, target) else null
        },
    )
    Surface(
        modifier = modifier.onSizeChanged { viewport = it },
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
    suggestion: String? = null,
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val confirmAccessibilityLabel = stringResource(R.string.confirm_action, confirmLabel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                RmeTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = value,
                    onValueChange = { value = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_name_label)) },
                )
                suggestion?.let { smartName ->
                    Text(
                        text = stringResource(R.string.smart_name_suggested, smartName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RmeTextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { value = smartName },
                    ) {
                        Text(stringResource(R.string.smart_name_use_suggestion))
                    }
                }
            }
        },
        confirmButton = {
            RmeTextButton(
                modifier = Modifier.semantics {
                    contentDescription = confirmAccessibilityLabel
                },
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            RmeTextButton(onClick = onDismiss) {
                Text(dismissLabel)
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
                RmeTextButton(onClick = { onMove(null) }) {
                    Text(stringResource(R.string.library_root_folder))
                }
                folders.forEach { folder ->
                    RmeTextButton(onClick = { onMove(folder.id) }) { Text(folder.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RmeTextButton(onClick = onDismiss) {
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
            RmeTextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            RmeTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
    )
}

private val LibrarySortOrder.labelResource: Int
    get() = when (this) {
        LibrarySortOrder.MODIFIED_DESC -> R.string.library_sort_updated
        LibrarySortOrder.CREATED_DESC -> R.string.library_sort_created
        LibrarySortOrder.DOCUMENT_DATE_DESC -> R.string.library_sort_document_date
        LibrarySortOrder.TITLE_ASC -> R.string.library_sort_title
    }

private sealed interface NamingDialog {
    val titleResource: Int
    val confirmResource: Int

    data object CreateFolder : NamingDialog {
        override val titleResource = R.string.library_create_folder_title
        override val confirmResource = R.string.library_create_action
    }

    data class MergeDocuments(val documentIds: List<String>) : NamingDialog {
        init {
            require(documentIds.size >= 2)
            require(documentIds.distinct().size == documentIds.size)
        }

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
internal fun libraryActionMessage(state: LibraryActionState): String? =
    libraryActionMessageResource(state)?.let { stringResource(it) }

internal fun libraryActionMessageResource(state: LibraryActionState): Int? = when (state) {
    LibraryActionState.Idle,
    LibraryActionState.Working,
    -> null
    is LibraryActionState.Succeeded ->
        when {
            state.action == LibraryActionSuccess.DELETED &&
                state.warning == LibraryWarning.DELETION_CLEANUP_PENDING ->
                R.string.library_delete_cleanup_pending
            else -> when (state.action) {
                LibraryActionSuccess.SAVED -> R.string.library_saved
                LibraryActionSuccess.RENAMED -> R.string.library_renamed
                LibraryActionSuccess.MOVED -> R.string.library_moved
                LibraryActionSuccess.DELETED -> R.string.recycle_moved
                LibraryActionSuccess.FOLDER_CREATED -> R.string.library_folder_created
                LibraryActionSuccess.FOLDER_RENAMED -> R.string.library_folder_renamed
                LibraryActionSuccess.FOLDER_MOVED -> R.string.library_moved
                LibraryActionSuccess.FOLDER_DELETED -> R.string.library_folder_deleted
                LibraryActionSuccess.MERGED -> R.string.library_merged
                LibraryActionSuccess.EXTRACTED -> R.string.library_extracted
                LibraryActionSuccess.SPLIT -> R.string.library_split
                LibraryActionSuccess.OCR_INDEXED -> R.string.library_ocr_indexed
            }
        }
    is LibraryActionState.Failed ->
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
            LibraryError.DELETION_RECOVERY_PENDING -> R.string.library_error_deletion_recovery_pending
            LibraryError.DELETION_STATE_UNCERTAIN -> R.string.library_error_deletion_state_uncertain
            LibraryError.CORRUPTED_RECORD -> R.string.library_error_corrupted
            LibraryError.SAVE_REQUIRED -> R.string.library_error_save_required
            LibraryError.OPERATION_INTERRUPTED -> R.string.library_error_interrupted
        }
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
            versionName = "1.6.0",
            versionCode = 17,
            gitRevision = "preview",
            showPrivacyInfo = false,
            showAbout = false,
            thumbnailUri = { null },
            onQueryChange = {},
            onFolderSelected = {},
            onSortOrderChange = {},
            onOpenDocument = { _, _ -> },
            onLoadMoreSearchResults = {},
            onRequestSmartName = {},
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
