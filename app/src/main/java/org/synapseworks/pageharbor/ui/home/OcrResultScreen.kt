package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.geometry.Rect
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.ui.ocr.scriptName
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.synapseworks.pageharbor.image.bitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.copyableOcrPreview
import org.synapseworks.pageharbor.ocr.displayText
import org.synapseworks.pageharbor.ocr.failedPageCount
import org.synapseworks.pageharbor.ocr.textFoundPageCount
import org.synapseworks.pageharbor.ui.ocr.OcrSpellingDialog
import org.synapseworks.pageharbor.ui.theme.PageHarborLayout
import org.synapseworks.pageharbor.ui.theme.PageHarborSpacing

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OcrResultScreen(
    result: OcrResult,
    pageUris: List<Uri>,
    pageMetadata: List<DocumentImageMetadata>,
    selectedPageIndex: Int,
    onSelectedPageChange: (Int) -> Unit,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRecognizeAgain: () -> Unit,
    onClearRecognizedText: () -> Unit,
    onCopyText: (String) -> Unit,
    onSaveSearchablePdf: (() -> Unit)? = null,
    onChangeScript: (() -> Unit)? = null,
    pageRotations: List<DocumentPageRotation> = emptyList(),
) {
    val pageCount = result.pages.size
    val selectedIndex = selectedPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val selectedPage = result.pages.getOrNull(selectedIndex)
    val selectedPageMetadata = pageMetadata.getOrNull(selectedIndex) ?: DocumentImageMetadata()
    val textFoundPageCount = result.textFoundPageCount()
    val context = LocalContext.current
    val emptyPageText = stringResource(R.string.ocr_preview_empty_page)
    val copyPayload = remember(result, emptyPageText, context) {
        copyableOcrPreview(result, { context.getString(R.string.ocr_preview_page_heading, it) }, emptyPageText)
    }
    val pageText = selectedPage?.displayText().orEmpty()
    var showSource by rememberSaveable { mutableStateOf(false) }
    val blocks = remember(pageText) { ocrReadoutBlocks(pageText) }
    val passages = remember(blocks, selectedPage?.layout) { sourcePassages(blocks, selectedPage?.layout) }
    var passage by remember(result, selectedIndex) { mutableStateOf<OcrSourcePassage?>(null) }
    val position = remember(selectedPage, selectedIndex) { DocumentPreviewPosition() }
    androidx.activity.compose.BackHandler(enabled = showSource) { showSource = false }
    val textScroll = key(selectedIndex) { rememberScrollState() }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    // OCR content and search terms may be sensitive. Keep the query only in this composition.
    var query by remember(result) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var spellingOpen by remember(result, selectedIndex) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    var matches by remember(result, query) { mutableStateOf(emptyList<OcrDocumentMatch>()) }
    var activeMatch by remember(result, query) { mutableIntStateOf(-1) }
    val active = matches.getOrNull(activeMatch)?.takeIf { it.page == selectedIndex }
    LaunchedEffect(result, query) {
        if (query.isNotBlank()) matches = withContext(Dispatchers.Default) {
            ocrDocumentMatches(result, query) { ensureActive() }
        }
    }
    fun jump(offset: Int) {
        if (matches.isEmpty()) return
        activeMatch = if (activeMatch < 0) {
            if (offset > 0) 0 else matches.lastIndex
        } else (activeMatch + offset + matches.size) % matches.size
        showSource = false
        focus.clearFocus()
        onSelectedPageChange(matches[activeMatch].page)
    }

    if (spellingOpen) {
        OcrSpellingDialog(
            sourceText = pageText,
            script = selectedPage?.actualScript,
            confirmLabel = R.string.ocr_spelling_copy_reviewed,
            onDismiss = { spellingOpen = false },
            onConfirm = { reviewed ->
                spellingOpen = false
                onCopyText(reviewed)
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ocr_clear_confirm)) },
            text = { Text(stringResource(R.string.ocr_clear_explanation)) },
            confirmButton = {
                RmeTextButton(onClick = { confirmClear = false; onClearRecognizedText() }) {
                    Text(stringResource(R.string.ocr_clear_action))
                }
            },
            dismissButton = {
                RmeTextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.manual_cancel)) }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!showSource && copyPayload != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        if (pageCount > 1) {
                            RmeSecondaryButton(
                                modifier = Modifier.weight(1f),
                                enabled = pageText.isNotBlank(),
                                onClick = { onCopyText(pageText) },
                            ) { Text(stringResource(R.string.ocr_copy_page), textAlign = TextAlign.Center) }
                        }
                        RmePrimaryButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onCopyText(copyPayload) },
                        ) {
                            Text(stringResource(if (pageCount == 1) R.string.ocr_copy_action else R.string.ocr_copy_all),
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.ocr_result_heading),
                    )
                },
                actions = {
                    if (textFoundPageCount > 0) {
                        RmeIconButton(onClick = {
                            findOpen = !findOpen
                            showSource = false
                            if (!findOpen) query = ""
                        }) {
                            Icon(
                                if (findOpen) Icons.Default.Close else Icons.Default.Search,
                                stringResource(if (findOpen) R.string.ocr_find_close else R.string.ocr_find),
                            )
                        }
                    }
                    Box {
                        RmeIconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.ocr_text_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onSaveSearchablePdf != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.searchable_pdf_save_action)) },
                                    onClick = { menuOpen = false; onSaveSearchablePdf() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ocr_recognize_again_action)) },
                                onClick = { menuOpen = false; onRecognizeAgain() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ocr_clear_action)) },
                                onClick = { menuOpen = false; confirmClear = true },
                            )
                        }
                    }
                },
                navigationIcon = {
                    RmeIconButton(onClick = { if (showSource) showSource = false else onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_back_action),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (findOpen && !showSource) {
                Column(
                    Modifier.widthIn(max = PageHarborLayout.readingContentMaxWidth).fillMaxWidth()
                        .padding(horizontal = PageHarborLayout.compactScreenHorizontalPadding),
                ) {
                    RmeTextField(
                        value = query,
                        onValueChange = { query = it.take(120) },
                        label = { Text(stringResource(R.string.ocr_find_document)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (query.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = when {
                                active != null -> stringResource(R.string.ocr_match_position, activeMatch + 1, matches.size)
                                matches.size == OCR_DOCUMENT_MATCH_LIMIT -> stringResource(R.string.ocr_match_limit, matches.size)
                                else -> androidx.compose.ui.res.pluralStringResource(R.plurals.ocr_find_count, matches.size, matches.size)
                            }
                            Text(
                                label,
                                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            RmeIconButton(onClick = { jump(-1) }, enabled = matches.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ocr_previous_match))
                            }
                            RmeIconButton(onClick = { jump(1) }, enabled = matches.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.ocr_next_match))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RmeFilterChip(
                    selected = !showSource,
                    onClick = { showSource = false },
                    label = { Text(stringResource(R.string.ocr_text_section_heading)) },
                )
                RmeFilterChip(
                    selected = showSource,
                    onClick = { focus.clearFocus(); showSource = true },
                    label = { Text(stringResource(R.string.ocr_source_tab)) },
                )
                if (showSource && pageCount > 1) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    RmeIconButton(onClick = { onSelectedPageChange(selectedIndex - 1) }, enabled = selectedIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ocr_previous_page_action))
                    }
                    Text(stringResource(R.string.ocr_page_indicator, selectedIndex + 1, pageCount))
                    RmeIconButton(onClick = { onSelectedPageChange(selectedIndex + 1) }, enabled = selectedIndex + 1 < pageCount) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.ocr_next_page_action))
                    }
                }
            }
            if (showSource) {
                key(result, selectedIndex) {
                    ScannedDocumentPreview(pageUris.getOrNull(selectedIndex), selectedPageMetadata,
                        selectedIndex + 1, pageCount, pageRotations.getOrNull(selectedIndex) ?: DocumentPageRotation.DEGREES_0,
                        passage?.bounds, position, Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = PageHarborLayout.readingContentMaxWidth)
                        .fillMaxSize()
                        .padding(
                            horizontal = PageHarborLayout.compactScreenHorizontalPadding,
                            vertical = PageHarborLayout.compactScreenVerticalPadding,
                        )
                        .verticalScroll(textScroll),
                    verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.large),
                ) {
                    OcrSummary(
                        pageCount = pageCount,
                        textFoundPageCount = textFoundPageCount,
                        failedPageCount = result.failedPageCount(),
                    )

                    if (pageCount > 1) {
                        OcrPageNavigation(
                            selectedPageIndex = selectedIndex,
                            pageCount = pageCount,
                            onSelectedPageChange = onSelectedPageChange,
                        )
                    }

                    if (selectedPage != null) {
                        if (selectedPage.actualScript != null || onChangeScript != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val label = selectedPage.actualScript?.let { scriptName(it) }
                                Text(
                                    if (label == null) stringResource(R.string.ocr_script_unknown)
                                    else stringResource(R.string.ocr_script_used, label),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (onChangeScript != null) {
                                    RmeTextButton(onClick = onChangeScript) {
                                        Text(stringResource(R.string.ocr_change_language))
                                    }
                                }
                            }
                        }
                        if (pageText.isNotBlank()) {
                            RmeTextButton(onClick = { spellingOpen = true }) {
                                Text(stringResource(R.string.ocr_spelling_review))
                            }
                        }
                        if (passages.isNotEmpty()) Text(stringResource(R.string.ocr_tap_source), style = MaterialTheme.typography.bodySmall)
                        OcrPageText(pageText, query, active, blocks, passages, passage) {
                            activeMatch = -1
                            position.lastViewport = IntSize.Zero
                            passage = it
                            focus.clearFocus()
                            showSource = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrSummary(
    pageCount: Int,
    textFoundPageCount: Int,
    failedPageCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Text(
            text = if (textFoundPageCount == 0) {
                stringResource(R.string.ocr_no_text)
            } else if (pageCount == 1) {
                stringResource(R.string.ocr_single_page_summary)
            } else {
                stringResource(R.string.ocr_page_summary, textFoundPageCount, pageCount)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (failedPageCount > 0) {
            Text(
                text = stringResource(R.string.ocr_partial_failure_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun OcrPageNavigation(
    selectedPageIndex: Int,
    pageCount: Int,
    onSelectedPageChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            text = stringResource(R.string.ocr_page_indicator, selectedPageIndex + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PageHarborSpacing.medium),
        ) {
            RmeSecondaryButton(
                modifier = Modifier.weight(1f),
                enabled = selectedPageIndex > 0,
                onClick = { onSelectedPageChange(selectedPageIndex - 1) },
            ) { Text(stringResource(R.string.ocr_previous_page_action)) }
            RmeSecondaryButton(
                modifier = Modifier.weight(1f),
                enabled = selectedPageIndex < pageCount - 1,
                onClick = { onSelectedPageChange(selectedPageIndex + 1) },
            ) { Text(stringResource(R.string.ocr_next_page_action)) }
        }
    }
}

@Composable
internal fun OcrPageText(
    displayText: String,
    query: String,
    active: OcrDocumentMatch?,
    blocks: List<OcrReadoutBlock>,
    passages: List<OcrSourcePassage>,
    selected: OcrSourcePassage?,
    onPassage: (OcrSourcePassage) -> Unit,
) {
    val openPassage by rememberUpdatedState(onPassage)
    val links = remember(passages) { passages.groupBy(OcrSourcePassage::block) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (displayText.isBlank()) {
            Text(
                modifier = Modifier.padding(PageHarborSpacing.medium),
                text = stringResource(R.string.ocr_preview_empty_page),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().padding(PageHarborSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small),
                ) {
                    for ((index, block) in blocks.withIndex()) {
                        val activeRange = active?.takeIf { it.block == index }?.range
                        val selectedColor = MaterialTheme.colorScheme.primaryContainer
                        val selectedText = MaterialTheme.colorScheme.onPrimaryContainer
                        val text = remember(block, query, activeRange, highlight, highlightText, selectedColor, selectedText, links, selected) {
                            buildAnnotatedString {
                                append(block.text)
                                for (link in links[index].orEmpty()) {
                                    addLink(LinkAnnotation.Clickable("source-${link.block}-${link.start}") { openPassage(link) }, link.start, link.end)
                                    if (link == selected) addStyle(SpanStyle(background = selectedColor, color = selectedText), link.start, link.end)
                                }
                                for (range in ocrMatchRanges(block.text, query)) {
                                    val emphasized = range == activeRange
                                    val color = if (emphasized) selectedText else highlightText
                                    val background = if (emphasized) selectedColor else highlight
                                    addStyle(SpanStyle(color = color, background = background), range.first, range.last + 1)
                                }
                            }
                        }
                        val requester = remember { BringIntoViewRequester() }
                        var layout by remember(block.text) { mutableStateOf<TextLayoutResult?>(null) }
                        val anchor = activeRange?.first ?: selected?.takeIf { it.block == index }?.start
                        LaunchedEffect(anchor, layout) {
                            if (anchor != null) {
                                val result = layout
                                if (result != null && anchor < result.layoutInput.text.length) {
                                    requester.bringIntoView(result.getBoundingBox(anchor))
                                }
                            }
                        }
                        val container = if (block.table) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
                        Text(
                            modifier = container.bringIntoViewRequester(requester),
                            text = text,
                            onTextLayout = { layout = it },
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                            fontFamily = if (block.table) FontFamily.Monospace else null,
                            softWrap = !block.table,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScannedDocumentPreview(
    pageUri: Uri?,
    imageMetadata: DocumentImageMetadata,
    pageNumber: Int,
    pageCount: Int,
    rotation: DocumentPageRotation,
    passage: Rect?,
    position: DocumentPreviewPosition,
    modifier: Modifier,
) {
    val description = stringResource(R.string.ocr_document_preview_description, pageNumber, pageCount)
    val resolver = LocalContext.current.contentResolver
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val target = bitmapDisplaySize(viewport.width, viewport.height)
    Box(modifier.onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
        val state by rememberManagedDocumentPreview(
            requestKey = listOf(pageUri, imageMetadata, rotation, target),
            decode = { if (pageUri == null || target == null) null else decodeOcrDocumentPreview(resolver, pageUri, imageMetadata, target) },
            // OCR uses the raw pixel buffer and the page's rotation, without additional EXIF transforms.
            transform = { org.synapseworks.pageharbor.document.transform(it, 1, rotation) },
        )
        when (val preview = state) {
            ManagedDocumentPreviewState.Loading -> Text(stringResource(R.string.ocr_preview_loading))
            ManagedDocumentPreviewState.Unavailable -> Text(stringResource(R.string.ocr_preview_unavailable))
            is ManagedDocumentPreviewState.Ready -> ZoomableDocumentPreview(
                bitmap = preview.owner.bitmap.asImageBitmap(), resetKey = pageUri to rotation,
                description = description, modifier = Modifier.fillMaxSize(), fitPortraitWidth = true,
                passage = passage, position = position,
            )
        }
    }
}

internal fun decodeOcrDocumentPreview(
    contentResolver: android.content.ContentResolver,
    pageUri: Uri,
    imageMetadata: DocumentImageMetadata,
    target: BitmapDisplaySize,
): Bitmap? {
    if (org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_SOURCE_LIMITS.imageConstraintViolation(imageMetadata) != null) return null
    return decodeDisplayBitmap({ contentResolver.openInputStream(pageUri) }, target)
}
