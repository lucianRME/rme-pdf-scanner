package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import org.synapseworks.pageharbor.image.ManualImageAdjustments
import org.synapseworks.pageharbor.document.crop.supportsImportCrop
import org.synapseworks.pageharbor.image.shadowGlareIntensity
import org.synapseworks.pageharbor.image.whiteningRecipe
import org.synapseworks.pageharbor.image.bleedThroughAmount
import org.synapseworks.pageharbor.image.creaseStainAmount
import org.synapseworks.pageharbor.image.moireAmount
import org.synapseworks.pageharbor.document.batch.SelectedPageBatchEditor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap
import org.synapseworks.pageharbor.ocr.looksDimForOcr
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryActionState
import org.synapseworks.pageharbor.library.LibraryFolder
import org.synapseworks.pageharbor.library.SmartNameUiState
import org.synapseworks.pageharbor.ocr.OcrUiState
import org.synapseworks.pageharbor.scanner.ScannerSpikeState
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing
import org.synapseworks.pageharbor.ui.theme.RmeModalBottomSheet
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTonalChoice
import org.synapseworks.pageharbor.ui.theme.rmeColors

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
    openedPageRequestId: Long,
    openedPagePersistentId: String?,
    pageToolsRequestId: Long = 0L,
    libraryFolders: List<LibraryFolder>,
    libraryActionState: LibraryActionState,
    smartNameState: SmartNameUiState?,
    importUiState: DocumentImportUiState,
    onPageFilterChange: (Long, DocumentFilter) -> Unit,
    onPageAdjustmentsChange: (DocumentPage, ManualImageAdjustments) -> Unit = { _, _ -> },
    batchEditor: SelectedPageBatchEditor? = null,
    documentRevision: Long = 0,
    documentOwnerToken: String = "",
    onReviewedShadowGlare: (DocumentPage, DocumentFilter, Long) -> Boolean = { _, _, _ -> false },
    onReviewedWhitening: (DocumentPage, DocumentFilter, Long) -> Boolean = { _, _, _ -> false },
    onReviewedBleedThrough: (DocumentPage, DocumentFilter, Long, String) -> Boolean = { _, _, _, _ -> false },
    onReviewedCreaseStain: (DocumentPage, DocumentFilter, Long, String) -> Boolean = { _, _, _, _ -> false },
    onReviewedMoire: (DocumentPage, DocumentFilter, Long, String) -> Boolean = { _, _, _, _ -> false },
    onBookSplit: (Int) -> Unit = {},
    onCurvature: (Int) -> Unit = {},
    onImportCrop: (Int) -> Unit = {},
    onObjectRemoval: (Int) -> Unit = {},
    onFingerRemoval: (Int) -> Unit = {},
    onCollage: () -> Unit = {},
    annotationEditor: org.synapseworks.pageharbor.document.annotation.AnnotationEditorViewModel? = null,
    onOpenAnnotations: (DocumentPage)->Unit = {},
    onAnnotationsApply: (DocumentPage,Long,String,org.synapseworks.pageharbor.document.annotation.AnnotationLayer)->Boolean = {_,_,_,_->false},
    historyState: org.synapseworks.pageharbor.document.history.HistoryUiState = org.synapseworks.pageharbor.document.history.HistoryUiState(),
    onDocumentUndo: () -> Unit = {},
    onDocumentRedo: () -> Unit = {},
    onPageRotate: (Long) -> Unit,
    onPageMove: (Long, Int) -> Unit,
    onPageRemove: (Long) -> Unit,
    onSaveToLibrary: (String) -> Unit,
    onExtractLibraryPages: (Set<String>, String, Boolean) -> Unit,
    onRenameLibraryDocument: (String, String) -> Unit,
    onRequestSmartName: (String) -> Unit,
    onMoveLibraryDocument: (String, String?) -> Unit,
    onDeleteLibraryDocument: (String) -> Unit,
    onConsumeLibraryAction: () -> Unit,
    onOpenPptx: () -> Unit = {},
    onOpenIntegrity: () -> Unit = {},
    onOpenPhoto: (Int) -> Unit = {},
    onOpenAssistance: () -> Unit = {},
    onOpenTranslation: () -> Unit = {},
    onOpenDocx: () -> Unit = {},
    onOpenWorkflows: () -> Unit = {},
    onBack: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onSharePdf: () -> Unit,
    onFitUploadLimit: () -> Unit = {},
    onShareSafeCopy: () -> Unit = {},
    onPasswordPdfExport: () -> Unit = {},
    onLocalPdfTools: () -> Unit = {},
    onExportPages: () -> Unit,
    onRecognizeText: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onManualScan: () -> Unit = {},
    onImportFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onDiscard: () -> Unit,
) {
    batchEditor?.let { if (it.state != null) BatchEditingDialog(it) }
    annotationEditor?.let { editor ->
        val draft=editor.state
        LaunchedEffect(documentPages,documentRevision,documentOwnerToken,draft?.page) {
            if(draft!=null&&!editor.current(documentPages.firstOrNull{it.id==draft.page.id},documentRevision,documentOwnerToken))editor.cancel()
        }
        if(draft!=null)AnnotationEditorDialog(editor,onAnnotationsApply)
    }
    var selectedPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var handledOpenPageRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var handledPageToolsRequestId by rememberSaveable { mutableStateOf(0L) }
    var showLibrarySaveDialog by rememberSaveable { mutableStateOf(false) }
    var showPageToolsDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable(documentOwnerToken) { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<DocumentSheet?>(null) }
    var adjustmentsPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var moirePageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var moireRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var moireOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var moireInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var creaseStainPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var creaseStainRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var creaseStainOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var creaseStainInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var bleedPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var bleedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var bleedOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var bleedInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var whiteningPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var whiteningRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var whiteningOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var whiteningInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var shadowPageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var shadowRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var shadowOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var shadowInitial by rememberSaveable { mutableStateOf<String?>(null) }
    var editPanel by rememberSaveable { mutableStateOf(DocumentEditPanel.None) }
    LaunchedEffect(documentPages, openedPageRequestId) {
        if (handledOpenPageRequestId != openedPageRequestId) {
            selectedPageId = openedPagePersistentId?.let { stableId ->
                documentPages.firstOrNull { page -> page.persistentId == stableId }?.id?.value
            } ?: documentPages.firstOrNull()?.id?.value
            handledOpenPageRequestId = openedPageRequestId
        } else if (documentPages.none { it.id.value == selectedPageId }) {
            selectedPageId = documentPages.firstOrNull()?.id?.value
        }
    }
    LaunchedEffect(pageToolsRequestId, libraryDocument?.documentId) {
        if (pageToolsRequestId > handledPageToolsRequestId && libraryDocument != null) {
            showPageToolsDialog = true
            handledPageToolsRequestId = pageToolsRequestId
        }
    }
    val selectedPageIndex = documentPages.indexOfFirst { it.id.value == selectedPageId }
        .takeIf { it >= 0 } ?: 0
    val selectedPage = documentPages.getOrNull(selectedPageIndex)
    val openMoire: (DocumentFilter?) -> Unit = { preset ->
        moirePageId=selectedPage?.id?.value;moireRevision=documentRevision
        moireOwner=documentOwnerToken;moireInitial=preset?.name
    }
    LaunchedEffect(selectedPage?.id,documentRevision,documentOwnerToken) {
        if(moirePageId!=selectedPage?.id?.value||moireRevision!=documentRevision||moireOwner!=documentOwnerToken)moirePageId=null
    }
    if(moireRevision==documentRevision&&moireOwner==documentOwnerToken) {
        selectedPage?.takeIf{it.id.value==moirePageId}?.let { page ->
            MoireDialog(page,selectedPageIndex+1,documentPages.size,
                DocumentFilter.entries.firstOrNull{it.name==moireInitial},onDismiss={moirePageId=null},onApply={filter ->
                    val accepted=onReviewedMoire(page,filter,documentRevision,documentOwnerToken)
                    if(accepted)moirePageId=null
                    accepted
                })
        }
    }
    val openCreaseStain: (DocumentFilter?) -> Unit = { preset ->
        creaseStainPageId=selectedPage?.id?.value;creaseStainRevision=documentRevision
        creaseStainOwner=documentOwnerToken;creaseStainInitial=preset?.name
    }
    LaunchedEffect(selectedPage?.id,documentRevision,documentOwnerToken) {
        if(creaseStainPageId!=selectedPage?.id?.value||creaseStainRevision!=documentRevision||creaseStainOwner!=documentOwnerToken)creaseStainPageId=null
    }
    if(creaseStainRevision==documentRevision&&creaseStainOwner==documentOwnerToken) {
        selectedPage?.takeIf{it.id.value==creaseStainPageId}?.let { page ->
            CreaseStainDialog(page,selectedPageIndex+1,documentPages.size,
                DocumentFilter.entries.firstOrNull{it.name==creaseStainInitial},onDismiss={creaseStainPageId=null},onApply={filter ->
                    val accepted=onReviewedCreaseStain(page,filter,documentRevision,documentOwnerToken)
                    if(accepted)creaseStainPageId=null
                    accepted
                })
        }
    }
    val openBleedThrough: (DocumentFilter?) -> Unit = { preset ->
        bleedPageId=selectedPage?.id?.value;bleedRevision=documentRevision
        bleedOwner=documentOwnerToken;bleedInitial=preset?.name
    }
    LaunchedEffect(selectedPage?.id,documentRevision,documentOwnerToken) {
        if(bleedPageId!=selectedPage?.id?.value||bleedRevision!=documentRevision||bleedOwner!=documentOwnerToken)bleedPageId=null
    }
    if(bleedRevision==documentRevision&&bleedOwner==documentOwnerToken) {
        selectedPage?.takeIf{it.id.value==bleedPageId}?.let { page ->
            BleedThroughDialog(page,selectedPageIndex+1,documentPages.size,
                DocumentFilter.entries.firstOrNull{it.name==bleedInitial},onDismiss={bleedPageId=null},onApply={filter ->
                    val accepted=onReviewedBleedThrough(page,filter,documentRevision,documentOwnerToken)
                    if(accepted)bleedPageId=null
                    accepted
                })
        }
    }
    val openWhitening: (DocumentFilter?) -> Unit = { preset ->
        whiteningPageId=selectedPage?.id?.value;whiteningRevision=documentRevision
        whiteningOwner=documentOwnerToken;whiteningInitial=preset?.name
    }
    LaunchedEffect(selectedPage?.id,documentRevision,documentOwnerToken) {
        if(whiteningPageId!=selectedPage?.id?.value||whiteningRevision!=documentRevision||whiteningOwner!=documentOwnerToken)whiteningPageId=null
    }
    if(whiteningRevision==documentRevision&&whiteningOwner==documentOwnerToken) {
        selectedPage?.takeIf{it.id.value==whiteningPageId}?.let { page ->
            BackgroundWhiteningDialog(page,selectedPageIndex+1,documentPages.size,
                DocumentFilter.entries.firstOrNull{it.name==whiteningInitial},onDismiss={whiteningPageId=null},onApply={filter ->
                    val accepted=onReviewedWhitening(page,filter,documentRevision)
                    if(accepted)whiteningPageId=null
                    accepted
                })
        }
    }
    val openShadowGlare: (DocumentFilter?) -> Unit = { preset ->
        shadowPageId=selectedPage?.id?.value;shadowRevision=documentRevision
        shadowOwner=documentOwnerToken;shadowInitial=preset?.name
    }
    LaunchedEffect(selectedPage?.id,documentRevision,documentOwnerToken) {
        if(shadowPageId!=selectedPage?.id?.value||shadowRevision!=documentRevision||shadowOwner!=documentOwnerToken)shadowPageId=null
    }
    if(shadowRevision==documentRevision&&shadowOwner==documentOwnerToken) {
        selectedPage?.takeIf{it.id.value==shadowPageId}?.let { page ->
            ShadowGlareDialog(page,selectedPageIndex+1,documentPages.size,
                DocumentFilter.entries.firstOrNull{it.name==shadowInitial},onDismiss={shadowPageId=null},onApply={filter ->
                    val accepted=onReviewedShadowGlare(page,filter,documentRevision)
                    if(accepted)shadowPageId=null
                    accepted
                })
        }
    }
    LaunchedEffect(selectedPage?.id) {
        if (selectedPage?.id?.value != adjustmentsPageId) adjustmentsPageId = null
    }
    selectedPage?.takeIf { it.id.value == adjustmentsPageId }?.let { page ->
        ManualAdjustmentsDialog(page, onDismiss = { adjustmentsPageId = null }, onApply = { settings ->
            onPageAdjustmentsChange(page, settings)
            adjustmentsPageId = null
        })
    }
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
    val sessionActionsEnabled = !importing && !libraryWorking && !historyState.busy
    val libraryMessage = libraryActionMessage(libraryActionState)
    val libraryEventId = when (libraryActionState) {
        is LibraryActionState.Succeeded -> libraryActionState.eventId
        is LibraryActionState.Failed -> libraryActionState.eventId
        LibraryActionState.Idle,
        LibraryActionState.Working,
        -> null
    }

    val reviewActionsEnabled = sessionActionsEnabled && !saving && !savingSearchablePdf &&
        !sharing && !exporting && !ocrRecognizing
    val layoutActionsEnabled = reviewActionsEnabled && batchEditor?.state == null
    val shareEnabled = !sharing && !importing && !libraryWorking
    val librarySaveLabel = if (libraryDocument == null) {
        R.string.library_save_action
    } else {
        R.string.library_save_changes_action
    }

    LaunchedEffect(libraryEventId) {
        if (libraryEventId != null && libraryMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(libraryMessage)
            onConsumeLibraryAction()
        }
    }

    var comparingSource by remember(documentOwnerToken, documentRevision, selectedPage?.id) { mutableStateOf(false) }
    var enhanceOpen by remember(documentOwnerToken, documentRevision, selectedPage?.id) { mutableStateOf(false) }
    var fullPreview by remember(documentOwnerToken, documentRevision, selectedPage?.id) { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val lightingSource = selectedPage?.takeIf {
        it.sourceCategory != DocumentSourceCategory.RENDERED_PDF_PAGE && it.contentType.startsWith("image/")
    }?.source
    var dimPage by remember(lightingSource) { mutableStateOf(false) }
    LaunchedEffect(lightingSource, resolver) {
        val uri = lightingSource?.toAndroidUri() ?: return@LaunchedEffect
        dimPage = withContext(Dispatchers.IO) {
            val sample = decodeDisplayBitmap({ resolver.openInputStream(uri) }, BitmapDisplaySize(160, 160))
                ?: return@withContext false
            try {
                val pixels = IntArray(sample.width * sample.height)
                sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                looksDimForOcr(sample.width, sample.height, pixels)
            } finally {
                sample.recycle()
            }
        }
    }
    val pagePreview = selectedPage?.let { page ->
        FilteredPreviewRequest(
            pageId = page.id.value,
            sourceKey = page.source.reference,
            filter = if (comparingSource) DocumentFilter.ORIGINAL else page.filter,
            adjustments = if (comparingSource) ManualImageAdjustments() else page.adjustments,
            annotations = page.annotations,
            rotation = page.rotation,
            imageMetadata = page.imageMetadata,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = libraryDocument?.title
                            ?: stringResource(R.string.document_result_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    val available = layoutActionsEnabled && activeSheet == null &&
                        editPanel == DocumentEditPanel.None && adjustmentsPageId == null &&
                        shadowPageId == null && whiteningPageId == null && bleedPageId == null &&
                        creaseStainPageId == null && moirePageId == null && annotationEditor?.state == null
                    if (pagePreview != null) {
                        RmeIconButton(
                            onClick = { fullPreview = true },
                            enabled = available,
                            modifier = Modifier.testTag("document-full-preview"),
                        ) {
                            Icon(Icons.Default.Fullscreen, stringResource(R.string.document_full_preview))
                        }
                    }
                    RmeIconButton(
                        onClick = onDocumentUndo,
                        enabled = available && historyState.canUndo,
                        modifier = Modifier.testTag("history-undo"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = historyState.undoAction?.let {
                                stringResource(R.string.history_undo, historyActionLabel(it))
                            } ?: stringResource(R.string.document_undo),
                        )
                    }
                    RmeIconButton(
                        onClick = onDocumentRedo,
                        enabled = available && historyState.canRedo,
                        modifier = Modifier.testTag("history-redo"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = historyState.redoAction?.let {
                                stringResource(R.string.history_redo, historyActionLabel(it))
                            } ?: stringResource(R.string.document_redo),
                        )
                    }
                },
                navigationIcon = {
                    RmeIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_back_action),
                        )
                    }
                },
            )
        },
        bottomBar = {
            DocumentBottomBar(
                librarySaveLabel = librarySaveLabel,
                canSaveToLibrary = hasProcessablePages && reviewActionsEnabled,
                canExportPdf = hasProcessablePages && sessionActionsEnabled && !saving,
                canAddPages = sessionActionsEnabled,
                canEdit = selectedPage != null && sessionActionsEnabled,
                canRecognize = hasProcessablePages && !ocrRecognizing && !importing && !libraryWorking,
                canShare = hasProcessablePages && shareEnabled,
                onShare = onSharePdf,
                onSaveToLibrary = { showLibrarySaveDialog = true },
                onExportPdf = onSavePdf,
                onAdd = { activeSheet = DocumentSheet.Add },
                onEdit = { editPanel = DocumentEditPanel.Tools },
                onOcr = {
                    if (libraryDocument != null || ocrUiState is OcrUiState.Success) {
                        onViewRecognizedText()
                    } else {
                        onRecognizeText()
                    }
                },
                onMore = { activeSheet = DocumentSheet.More },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding).consumeWindowInsets(padding),
        ) {
            val operationVisible =
                libraryWorking || saving || savingSearchablePdf || sharing || exporting ||
                    ocrRecognizing || importUiState is DocumentImportUiState.Processing
            val previewHeightFraction = if (operationVisible) 0.48f else 0.76f
            val previewMinHeight = minOf(
                PageHarborLayout.editorDocumentPreviewMinHeight,
                (maxHeight * 0.5f).coerceAtLeast(72.dp),
            )
            val availablePreviewHeight = minOf(maxHeight * previewHeightFraction, maxHeight - 104.dp)
                .coerceAtLeast(previewMinHeight)
            val previewMaxHeight = minOf(
                PageHarborLayout.editorDocumentPreviewMaxHeight,
                availablePreviewHeight,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = PageHarborLayout.expandedContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PageHarborLayout.compactScreenHorizontalPadding,
                        vertical = PageHarborSpacing.small,
                    ),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                if (dimPage && !fullPreview) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            text = stringResource(R.string.ocr_lighting_warning),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("ocr-lighting-warning"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                selectedPage?.let { page ->
                    if (fullPreview) Spacer(Modifier.height(previewMaxHeight))
                    else FilteredDocumentPreview(
                        request = requireNotNull(pagePreview),
                        pageUri = page.source.toAndroidUri(),
                        pageNumber = selectedPageIndex + 1,
                        pageCount = documentPages.size,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = previewMinHeight,
                        maxHeight = previewMaxHeight,
                    )
                } ?: EmptyDocumentWorkspace(
                    modifier = Modifier,
                    pageCount = displayedPageCount,
                )

                if (selectedPage != null) {
                    val canCompare = selectedPage.filter != DocumentFilter.ORIGINAL ||
                        selectedPage.adjustments != ManualImageAdjustments()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall, Alignment.CenterHorizontally),
                    ) {
                        if (selectedPage.supportsImportCrop()) {
                            RmeTextButton(
                                onClick = { onImportCrop(selectedPageIndex) },
                                enabled = layoutActionsEnabled,
                                modifier = Modifier.weight(1f).testTag("document-crop"),
                            ) { Text(stringResource(R.string.document_quick_crop), textAlign = TextAlign.Center) }
                        }
                        RmeTextButton(
                            onClick = { comparingSource = false; enhanceOpen = true },
                            enabled = layoutActionsEnabled,
                            modifier = Modifier.weight(1f).testTag("document-quick-enhance"),
                        ) { Text(stringResource(R.string.filter_enhance), textAlign = TextAlign.Center) }
                        if (canCompare) {
                            RmeTextButton(
                                onClick = { comparingSource = !comparingSource },
                                enabled = layoutActionsEnabled,
                                modifier = Modifier.weight(1f).testTag("document-compare-enhancement"),
                            ) {
                                Text(stringResource(
                                    if (comparingSource) R.string.document_compare_edited else R.string.document_compare,
                                ), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (comparingSource) {
                        Text(
                            stringResource(R.string.document_compare_before),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                PageNavigator(
                    selectedPageIndex = selectedPageIndex,
                    pageCount = documentPages.size,
                    onSelectedPageChange = { index ->
                        selectedPageId = documentPages[index].id.value
                    },
                )

                DocumentHistoryStatus(historyState, compact = true)
                if (operationVisible) {
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

    if (fullPreview && selectedPage != null && pagePreview != null) {
        Dialog(
            onDismissRequest = { fullPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize().testTag("document-full-preview-dialog")) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RmeIconButton(
                            onClick = { fullPreview = false },
                            modifier = Modifier.testTag("document-exit-full-preview"),
                        ) { Icon(Icons.Default.Close, stringResource(R.string.document_close_full_preview)) }
                        Text(
                            stringResource(R.string.ocr_page_indicator, selectedPageIndex + 1, documentPages.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (comparingSource) Text(stringResource(R.string.document_compare_before))
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        FilteredDocumentPreview(
                            request = pagePreview,
                            pageUri = selectedPage.source.toAndroidUri(),
                            pageNumber = selectedPageIndex + 1,
                            pageCount = documentPages.size,
                            modifier = Modifier.fillMaxSize(),
                            minHeight = maxHeight,
                            maxHeight = maxHeight,
                        )
                    }
                }
            }
        }
    }

    if (enhanceOpen && selectedPage != null) {
        QuickEnhanceSheet(
            page = selectedPage,
            onDismiss = { enhanceOpen = false },
            onApply = { filter ->
                enhanceOpen = false
                onPageFilterChange(selectedPage.id.value, filter)
            },
        )
    }

    if (editPanel != DocumentEditPanel.None && selectedPage != null) {
        DocumentEditSheet(
            panel = editPanel,
            page = selectedPage,
            selectedPageIndex = selectedPageIndex,
            pageCount = documentPages.size,
            enabled = !importing && !libraryWorking,
            layoutActionsEnabled = layoutActionsEnabled,
            onDone = { editPanel = DocumentEditPanel.None },
            onBackToTools = { editPanel = DocumentEditPanel.Tools },
            onOpenFilters = { editPanel = DocumentEditPanel.Filters },
            onOpenAdjustments = { adjustmentsPageId = selectedPage.id.value },
            onOpenBatchEdit = batchEditor?.let { it::open },
            onOpenShadowGlare = { openShadowGlare(null) },
            onOpenWhitening = { openWhitening(null) },
            onOpenBleedThrough = { openBleedThrough(null) },
            onOpenCreaseStain = { openCreaseStain(null) },
            onOpenMoire = { openMoire(null) },
            onBookSplit = { onBookSplit(selectedPageIndex) },
            onCurvature = { onCurvature(selectedPageIndex) },
            onObjectRemoval = { onObjectRemoval(selectedPageIndex) },
            onFingerRemoval = { onFingerRemoval(selectedPageIndex) },
            onCollage = onCollage,
            onAnnotations = annotationEditor?.let { { onOpenAnnotations(selectedPage) } },
            onFilterSelected = { filter ->
                when {
                    filter.shadowGlareIntensity != null -> openShadowGlare(filter)
                    filter.whiteningRecipe != null -> openWhitening(filter)
                    filter.bleedThroughAmount != null -> openBleedThrough(filter)
                    filter.creaseStainAmount != null -> openCreaseStain(filter)
                    filter.moireAmount != null -> openMoire(filter)
                    else -> onPageFilterChange(selectedPage.id.value, filter)
                }
            },
            onRotate = { onPageRotate(selectedPage.id.value) },
            onMove = { offset -> onPageMove(selectedPage.id.value, offset) },
            onRemove = { onPageRemove(selectedPage.id.value) },
        )
    }

    when (activeSheet) {
        DocumentSheet.Add -> DocumentAddSheet(
            canAddPages = canAddPages && sessionActionsEnabled,
            onDismiss = { activeSheet = null },
            onScanPages = { activeSheet = null; onManualScan() },
            onImportFiles = {
                activeSheet = null
                onImportFiles()
            },
        )

        DocumentSheet.More -> DocumentMoreSheet(
            hasPages = hasProcessablePages,
            librarySaveEnabled = reviewActionsEnabled,
            shareEnabled = shareEnabled,
            pdfExportEnabled = sessionActionsEnabled && !saving,
            searchablePdfEnabled = sessionActionsEnabled && !savingSearchablePdf,
            pageExportEnabled = sessionActionsEnabled && !exporting,
            secondaryActionsEnabled = sessionActionsEnabled,
            libraryDocument = libraryDocument,
            ocrAvailable = libraryDocument != null || ocrUiState is OcrUiState.Success,
            onDismiss = { activeSheet = null },
            onSaveToLibrary = {
                activeSheet = null
                showLibrarySaveDialog = true
            },
            onOpenPptx = { activeSheet = null;onOpenPptx() },
            onOpenIntegrity = { activeSheet = null;onOpenIntegrity() },
            onOpenPhoto = { activeSheet = null;onOpenPhoto(selectedPageIndex) },
            onOpenAssistance = { activeSheet = null;onOpenAssistance() },
            onOpenTranslation = { activeSheet = null;onOpenTranslation() },
            onOpenDocx = { activeSheet = null;onOpenDocx() },
            onOpenWorkflows = { activeSheet = null;onOpenWorkflows() },
            onSavePdf = {
                activeSheet = null
                onSavePdf()
            },
            onSharePdf = {
                activeSheet = null
                onSharePdf()
            },
            onSaveSearchablePdf = {
                activeSheet = null
                onSaveSearchablePdf()
            },
            onFitUploadLimit = {
                activeSheet = null
                onFitUploadLimit()
            },
            onShareSafeCopy = {
                activeSheet = null
                onShareSafeCopy()
            },
            onLocalPdfTools = {
                activeSheet = null
                onLocalPdfTools()
            },
            onPasswordPdfExport = {
                activeSheet = null
                onPasswordPdfExport()
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
                showDiscardDialog = true
            },
        )

        null -> Unit
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.document_discard_title)) },
            text = { Text(stringResource(R.string.document_discard_confirmation)) },
            confirmButton = {
                RmeTextButton(
                    enabled = reviewActionsEnabled,
                    onClick = {
                        showDiscardDialog = false
                        onDiscard()
                    },
                ) {
                    Text(
                        stringResource(R.string.home_clear_scan_result),
                        color = if (reviewActionsEnabled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            },
            dismissButton = {
                RmeTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
        LaunchedEffect(libraryDocument.documentId) {
            onRequestSmartName(libraryDocument.documentId)
        }
        RenameDocumentDialog(
            initialTitle = libraryDocument.title,
            suggestion = (smartNameState as? SmartNameUiState.Available)?.suggestion?.name,
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
    librarySaveLabel: Int,
    canSaveToLibrary: Boolean,
    canExportPdf: Boolean,
    canAddPages: Boolean,
    canEdit: Boolean,
    canRecognize: Boolean,
    canShare: Boolean,
    onShare: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onExportPdf: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onOcr: () -> Unit,
    onMore: () -> Unit,
) {
    val rmeColors = MaterialTheme.rmeColors
    Surface(
        modifier = Modifier.drawBehind {
            drawLine(
                color = rmeColors.divider,
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
        },
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(BottomAppBarDefaults.windowInsets)
                .padding(horizontal = PageHarborSpacing.medium, vertical = PageHarborSpacing.extraSmall),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                modifier = Modifier.widthIn(max = PageHarborLayout.readingContentMaxWidth).fillMaxWidth(),
            ) {
                val showActionIcons = maxWidth >= 360.dp * LocalDensity.current.fontScale
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    RmePrimaryButton(
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("document-save-library"),
                        enabled = canSaveToLibrary,
                        onClick = onSaveToLibrary,
                    ) {
                        if (showActionIcons) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(librarySaveLabel), textAlign = TextAlign.Center)
                    }
                    RmeSecondaryButton(
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("document-export-pdf"),
                        enabled = canExportPdf,
                        onClick = onExportPdf,
                    ) {
                        if (showActionIcons) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.document_export_pdf), textAlign = TextAlign.Center)
                    }
                }
            }
            Row(
                modifier = Modifier.widthIn(max = PageHarborLayout.readingContentMaxWidth).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.extraSmall),
            ) {
                listOf(
                    DocumentBarAction(Icons.Default.Add, R.string.document_action_add, canAddPages, onAdd),
                    DocumentBarAction(Icons.Default.Edit, R.string.document_action_edit, canEdit, onEdit),
                    DocumentBarAction(Icons.Default.TextFields, R.string.document_action_ocr, canRecognize, onOcr),
                    DocumentBarAction(Icons.Default.Share, R.string.document_action_share, canShare, onShare),
                    DocumentBarAction(Icons.Default.MoreHoriz, R.string.document_action_more, true, onMore),
                ).forEach { action ->
                    val label = stringResource(action.labelResource)
                    val accessibleLabel = if (action.labelResource == R.string.document_action_share) {
                        stringResource(R.string.document_share_copy)
                    } else {
                        label
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .semantics { contentDescription = accessibleLabel }
                            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
                            .padding(vertical = PageHarborSpacing.extraSmall),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            modifier = Modifier.size(24.dp),
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = if (action.enabled) rmeColors.iconSecondary else rmeColors.iconDisabled,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = if (action.enabled) MaterialTheme.colorScheme.onSurface else rmeColors.textDisabled,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickEnhanceSheet(
    page: DocumentPage,
    onDismiss: () -> Unit,
    onApply: (DocumentFilter) -> Unit,
) {
    var filter by remember(page) { mutableStateOf(DocumentFilter.DOCUMENT_CLEANUP) }
    var before by remember(page) { mutableStateOf(false) }
    RmeModalBottomSheet(onDismissRequest = onDismiss) {
        SheetContentColumn(title = stringResource(R.string.filter_enhance)) {
            val previewFilter = if (before) DocumentFilter.ORIGINAL else filter
            FilteredDocumentPreview(
                FilteredPreviewRequest(page.id.value, page.source.reference, previewFilter, page.rotation,
                    page.imageMetadata, if (before) ManualImageAdjustments() else page.adjustments, page.annotations),
                page.source.toAndroidUri(),
                pageNumber = 1,
                pageCount = 1,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 96.dp,
                maxHeight = 240.dp,
            )
            val choices = listOf(
                DocumentFilter.DOCUMENT_CLEANUP to R.string.document_clean_color,
                DocumentFilter.BLACK_AND_WHITE to R.string.document_black_white_short,
                DocumentFilter.ORIGINAL to R.string.document_no_filter,
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((option, label) in choices) {
                    RmeFilterChip(
                        selected = option == filter && !before,
                        onClick = { filter = option; before = false },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RmeTextButton(onClick = { before = !before }) {
                    Text(stringResource(if (before) R.string.document_compare_edited else R.string.document_compare))
                }
                RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.manual_cancel)) }
            }
            RmePrimaryButton(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("document-enhance-apply"),
                onClick = { onApply(filter) },
                enabled = !before && filter != page.filter,
            ) { Text(stringResource(R.string.manual_apply)) }
        }
    }
}

@Composable
private fun DocumentEditSheet(
    panel: DocumentEditPanel,
    page: DocumentPage,
    selectedPageIndex: Int,
    pageCount: Int,
    enabled: Boolean,
    layoutActionsEnabled: Boolean,
    onDone: () -> Unit,
    onBackToTools: () -> Unit,
    onOpenFilters: () -> Unit,
    onFilterSelected: (DocumentFilter) -> Unit,
    onOpenAdjustments: () -> Unit,
    onOpenBatchEdit: (() -> Unit)?,
    onOpenShadowGlare: () -> Unit,
    onOpenWhitening: () -> Unit,
    onOpenBleedThrough: () -> Unit,
    onOpenCreaseStain: () -> Unit,
    onOpenMoire: () -> Unit,
    onBookSplit: () -> Unit,
    onCurvature: () -> Unit,
    onObjectRemoval: () -> Unit,
    onFingerRemoval: () -> Unit,
    onCollage: () -> Unit,
    onAnnotations: (() -> Unit)?,
    onRotate: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val showingFilters = panel == DocumentEditPanel.Filters
    fun apply(action: () -> Unit) {
        onDone()
        action()
    }

    RmeModalBottomSheet(onDismissRequest = onDone) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageHarborSpacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showingFilters) {
                RmeIconButton(onClick = onBackToTools) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.document_edit_back),
                    )
                }
            }
            Text(
                modifier = Modifier.weight(1f).semantics { heading() },
                text = stringResource(
                    if (showingFilters) R.string.document_edit_filters_title else R.string.document_edit_title,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            RmeTextButton(onClick = onDone) { Text(stringResource(R.string.document_edit_done)) }
        }
        key(panel) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = PageHarborSpacing.extraLarge),
            ) {
                if (showingFilters) {
                    FilterSelector(
                        selectedFilter = page.filter,
                        enabled = enabled,
                        onFilterSelected = { filter -> apply { onFilterSelected(filter) } },
                    )
                } else {
                    EditGroupHeading(R.string.document_edit_pages)
                    SheetActionRow(
                        icon = Icons.AutoMirrored.Filled.RotateRight,
                        title = stringResource(R.string.page_rotate_action),
                        enabled = enabled,
                        onClick = { apply(onRotate) },
                    )
                    if (pageCount > 1) {
                        SheetActionRow(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            title = stringResource(R.string.page_move_earlier_action),
                            enabled = enabled && selectedPageIndex > 0,
                            onClick = { apply { onMove(-1) } },
                        )
                        SheetActionRow(
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            title = stringResource(R.string.page_move_later_action),
                            enabled = enabled && selectedPageIndex < pageCount - 1,
                            onClick = { apply { onMove(1) } },
                        )
                    }
                    SheetActionRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(R.string.page_remove_action),
                        supportingText = if (pageCount == 1) {
                            stringResource(R.string.page_remove_final_disabled)
                        } else {
                            null
                        },
                        enabled = enabled && pageCount > 1,
                        destructive = true,
                        onClick = { apply(onRemove) },
                    )
                    EditGroupHeading(R.string.document_edit_appearance)
                    SheetActionRow(
                        icon = Icons.Default.FilterAlt,
                        title = stringResource(R.string.document_edit_filter),
                        enabled = enabled,
                        onClick = onOpenFilters,
                    )
                    listOf(
                        Triple(Icons.Default.Brightness6, R.string.manual_adjust_title, onOpenAdjustments),
                        Triple(Icons.Default.Flare, R.string.shadow_title, onOpenShadowGlare),
                        Triple(Icons.Default.FormatColorFill, R.string.whitening_title, onOpenWhitening),
                        Triple(Icons.Default.LayersClear, R.string.bleed_title, onOpenBleedThrough),
                        Triple(Icons.Default.CleaningServices, R.string.crease_stain_title, onOpenCreaseStain),
                        Triple(Icons.Default.GridOff, R.string.moire_title, onOpenMoire),
                        Triple(Icons.Default.Straighten, R.string.curve_title, onCurvature),
                        Triple(Icons.Default.Brush, R.string.removal_title, onObjectRemoval),
                        Triple(Icons.Default.PanTool, R.string.finger_removal_title, onFingerRemoval),
                    ).forEach { (icon, label, action) ->
                        SheetActionRow(
                            icon = icon,
                            title = stringResource(label),
                            enabled = enabled,
                            onClick = { apply(action) },
                        )
                    }
                    if (onAnnotations != null) {
                        EditGroupHeading(R.string.document_edit_markup)
                        SheetActionRow(
                            modifier = Modifier.testTag("open-annotations"),
                            icon = Icons.Default.Edit,
                            title = stringResource(R.string.annotation_title),
                            enabled = layoutActionsEnabled,
                            onClick = { apply(onAnnotations) },
                        )
                    }
                    EditGroupHeading(R.string.document_edit_layout)
                    SheetActionRow(
                        icon = Icons.Default.ContentCut,
                        title = stringResource(R.string.book_title),
                        enabled = enabled,
                        onClick = { apply(onBookSplit) },
                    )
                    SheetActionRow(
                        modifier = Modifier.testTag("open-collage"),
                        icon = Icons.Default.Edit,
                        title = stringResource(R.string.document_edit_collage),
                        enabled = layoutActionsEnabled,
                        onClick = { apply(onCollage) },
                    )
                    if (onOpenBatchEdit != null) {
                        SheetActionRow(
                            icon = Icons.Default.Edit,
                            title = stringResource(R.string.batch_title),
                            enabled = enabled,
                            onClick = { apply(onOpenBatchEdit) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditGroupHeading(label: Int) {
    Text(
        modifier = Modifier
            .padding(horizontal = PageHarborSpacing.large, vertical = PageHarborSpacing.small)
            .semantics { heading() },
        text = stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PageNavigator(
    selectedPageIndex: Int,
    pageCount: Int,
    onSelectedPageChange: (Int) -> Unit,
) {
    if (pageCount <= 0) return
    if (pageCount == 1) {
        Text(
            text = stringResource(R.string.ocr_page_indicator, 1, 1),
            modifier = Modifier.fillMaxWidth().padding(vertical = PageHarborSpacing.small),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var showJump by rememberSaveable { mutableStateOf(false) }
    var targetPage by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RmeIconButton(enabled = selectedPageIndex > 0, onClick = { onSelectedPageChange(selectedPageIndex - 1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ocr_previous_page_action))
        }
        RmeTextButton(
            modifier = Modifier.widthIn(min = 104.dp).semantics { liveRegion = LiveRegionMode.Polite },
            onClick = { targetPage = (selectedPageIndex + 1).toString(); showJump = true },
        ) {
            Text(stringResource(R.string.ocr_page_indicator, selectedPageIndex + 1, pageCount))
        }
        RmeIconButton(enabled = selectedPageIndex < pageCount - 1, onClick = { onSelectedPageChange(selectedPageIndex + 1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.ocr_next_page_action))
        }
    }
    if (showJump) {
        val destination = targetPage.toIntOrNull()?.takeIf { it in 1..pageCount }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text(stringResource(R.string.document_go_to_page)) },
            text = {
                RmeTextField(
                    value = targetPage,
                    onValueChange = { value -> if (value.length <= 10 && value.all(Char::isDigit)) targetPage = value },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.document_page_range, pageCount)) },
                    isError = destination == null,
                )
            },
            confirmButton = {
                RmeTextButton(enabled = destination != null, onClick = {
                    destination?.let { onSelectedPageChange(it - 1) }; showJump = false
                }) { Text(stringResource(R.string.document_go)) }
            },
            dismissButton = { RmeTextButton(onClick = { showJump = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun EmptyDocumentWorkspace(modifier: Modifier, pageCount: Int) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PageHarborLayout.editorDocumentPreviewMinHeight),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.rmeColors.previewBackground,
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
    onScanPages: () -> Unit,
    onImportFiles: () -> Unit,
) {
    RmeModalBottomSheet(onDismissRequest = onDismiss) {
        SheetContentColumn(title = stringResource(R.string.document_add_title)) {
            SheetActionRow(
                icon = Icons.Default.CameraAlt,
                title = stringResource(R.string.capture_add_scan),
                supportingText = stringResource(R.string.document_add_pages_description),
                enabled = canAddPages,
                onClick = onScanPages,
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
    shareEnabled: Boolean,
    pdfExportEnabled: Boolean,
    searchablePdfEnabled: Boolean,
    pageExportEnabled: Boolean,
    secondaryActionsEnabled: Boolean,
    libraryDocument: LibraryDocumentReference?,
    ocrAvailable: Boolean,
    onDismiss: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onOpenPptx: () -> Unit,
    onOpenIntegrity: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenAssistance: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenDocx: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onSavePdf: () -> Unit,
    onSharePdf: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onFitUploadLimit: () -> Unit,
    onShareSafeCopy: () -> Unit,
    onPasswordPdfExport: () -> Unit,
    onLocalPdfTools: () -> Unit,
    onExportPages: () -> Unit,
    onOpenPageTools: () -> Unit,
    onViewRecognizedText: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDiscard: () -> Unit,
) {
    RmeModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("document-more-sheet"),
    ) {
        SheetContentColumn(title = stringResource(R.string.document_more_title)) {
            EditGroupHeading(R.string.document_actions_output)
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
                icon = Icons.Default.Share,
                title = stringResource(R.string.document_share_copy),
                supportingText = stringResource(R.string.document_share_copy_description),
                enabled = hasPages && shareEnabled,
                onClick = onSharePdf,
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
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.document_export_docx),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenDocx,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.document_export_pptx),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenPptx,
            )

            EditGroupHeading(R.string.document_actions_copies)
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.share_safe_title),
                supportingText = stringResource(R.string.share_safe_description),
                enabled = hasPages && pdfExportEnabled,
                onClick = onShareSafeCopy,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.FitScreen,
                title = stringResource(R.string.fit_limit_title),
                supportingText = stringResource(R.string.fit_limit_workspace_description),
                enabled = hasPages && pdfExportEnabled,
                onClick = onFitUploadLimit,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.PictureAsPdf,
                title = stringResource(R.string.document_local_tools),
                supportingText = stringResource(R.string.document_local_tools_description),
                enabled = hasPages && pdfExportEnabled,
                onClick = onLocalPdfTools,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.PictureAsPdf,
                title = stringResource(R.string.protect_action),
                supportingText = stringResource(R.string.protect_action_description),
                enabled = hasPages && pdfExportEnabled,
                onClick = onPasswordPdfExport,
            )

            EditGroupHeading(R.string.document_actions_tools)
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.document_integrity_action),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenIntegrity,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Edit,
                title = stringResource(R.string.document_photo_restoration_action),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenPhoto,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Search,
                title = stringResource(R.string.document_retrieval_action),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenAssistance,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Translate,
                title = stringResource(R.string.document_translation_action),
                enabled = hasPages && secondaryActionsEnabled,
                onClick = onOpenTranslation,
            )
            HorizontalDivider()
            SheetActionRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.document_workflows_action),
                enabled = secondaryActionsEnabled,
                onClick = onOpenWorkflows,
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
            EditGroupHeading(R.string.document_actions_manage)
            if (libraryDocument != null) {
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
                HorizontalDivider()
            }
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
    supportingText: String? = null,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = modifier
            .semantics { if (!enabled) disabled() }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
            )
        },
        headlineContent = { Text(text = title, color = contentColor) },
        supportingContent = supportingText?.let { description ->
            {
                Text(
                    text = description,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
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
                        message = pluralStringResource(
                            R.plurals.import_progress_with_pages,
                            importUiState.preparedPages,
                            importUiState.completedItems,
                            importUiState.totalItems,
                            importUiState.preparedPages,
                        ),
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
            RmeTextButton(
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageHarborSpacing.large),
        verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
    ) {
        filterSelectorOptions.forEach { option ->
            val accessibleLabel = stringResource(option.contentDescriptionRes)
            RmeTonalChoice(
                selected = option.filter == selectedFilter,
                enabled = enabled,
                onClick = { onFilterSelected(option.filter) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = accessibleLabel
                },
            ) { Text(stringResource(option.labelRes)) }
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
                RmeTextField(
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
            RmeTextButton(enabled = title.isNotBlank(), onClick = { onSave(title) }) {
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
            RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
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
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("library-page-selection")) {
                    itemsIndexed(pages, key = { _, page -> page.id.value }) { index, page ->
                    val persistentId = page.persistentId
                    RmeTonalChoice(
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
                    ) { Text(stringResource(R.string.library_page_number, index + 1)) }
                    }
                }
                RmeTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_document_title_label)) },
                )
                RmePrimaryButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIds.isNotEmpty() && title.isNotBlank() && allPagesPersisted,
                    onClick = { onApply(selectedIds, title, false) },
                ) {
                    Text(stringResource(R.string.library_extract_copy_action))
                }
                RmeSecondaryButton(
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
            RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.about_close)) }
        },
    )
}

@Composable
private fun RenameDocumentDialog(
    initialTitle: String,
    suggestion: String?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.document_rename_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
            ) {
                RmeTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = title,
                    onValueChange = { title = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_document_title_label)) },
                )
                suggestion?.let { smartName ->
                    Text(
                        text = stringResource(R.string.smart_name_suggested, smartName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RmeTextButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { title = smartName },
                    ) {
                        Text(stringResource(R.string.smart_name_use_suggestion))
                    }
                }
            }
        },
        confirmButton = {
            RmeTextButton(
                enabled = title.isNotBlank(),
                onClick = { onRename(title) },
            ) {
                Text(stringResource(R.string.library_rename_action))
            }
        },
        dismissButton = {
            RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.smart_name_keep_current)) }
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
                RmeTextButton(onClick = { onMove(null) }) {
                    Text(stringResource(R.string.library_root_folder))
                }
                folders.forEach { folder ->
                    RmeTextButton(onClick = { onMove(folder.id) }) {
                        Text(folder.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
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
            RmeTextButton(
                contentColor = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            ) {
                Text(stringResource(R.string.library_delete_action))
            }
        },
        dismissButton = {
            RmeTextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
