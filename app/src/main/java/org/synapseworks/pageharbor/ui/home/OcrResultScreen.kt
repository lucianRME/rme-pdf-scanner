package org.synapseworks.pageharbor.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DEFAULT_DOCUMENT_INPUT_LIMITS
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.imageConstraintViolation
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.copyableOcrPreview
import org.synapseworks.pageharbor.ocr.displayText
import org.synapseworks.pageharbor.ocr.failedPageCount
import org.synapseworks.pageharbor.ocr.textFoundPageCount
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
) {
    val pageCount = result.pages.size
    val selectedIndex = selectedPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val selectedPage = result.pages.getOrNull(selectedIndex)
    val selectedPageMetadata = pageMetadata.getOrNull(selectedIndex) ?: DocumentImageMetadata()
    val textFoundPageCount = result.textFoundPageCount()
    val context = LocalContext.current
    val copyPayload = copyableOcrPreview(
        result = result,
        pageHeading = { context.getString(R.string.ocr_preview_page_heading, it) },
        emptyPageText = stringResource(R.string.ocr_preview_empty_page),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        modifier = Modifier.semantics { heading() },
                        text = stringResource(R.string.ocr_result_heading),
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = PageHarborLayout.readingContentMaxWidth)
                    .fillMaxSize()
                    .padding(
                        horizontal = PageHarborLayout.compactScreenHorizontalPadding,
                        vertical = PageHarborLayout.compactScreenVerticalPadding,
                    )
                    .verticalScroll(rememberScrollState()),
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

                selectedPage?.let { page ->
                    OcrPageText(page)
                    ScannedDocumentPreview(
                        pageUri = pageUris.getOrNull(selectedIndex),
                        imageMetadata = selectedPageMetadata,
                        pageNumber = selectedIndex + 1,
                        pageCount = pageCount,
                    )
                }

                OcrActions(
                    copyPayload = copyPayload,
                    onCopyText = onCopyText,
                    onRecognizeAgain = onRecognizeAgain,
                    onClearRecognizedText = onClearRecognizedText,
                )
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
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = selectedPageIndex > 0,
                onClick = { onSelectedPageChange(selectedPageIndex - 1) },
            ) { Text(stringResource(R.string.ocr_previous_page_action)) }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = selectedPageIndex < pageCount - 1,
                onClick = { onSelectedPageChange(selectedPageIndex + 1) },
            ) { Text(stringResource(R.string.ocr_next_page_action)) }
        }
    }
}

@Composable
private fun OcrPageText(page: OcrPageResult) {
    val displayText = page.displayText()
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.ocr_text_section_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
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
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PageHarborSpacing.medium),
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannedDocumentPreview(
    pageUri: Uri?,
    imageMetadata: DocumentImageMetadata,
    pageNumber: Int,
    pageCount: Int,
) {
    val description = stringResource(R.string.ocr_document_preview_description, pageNumber, pageCount)
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.ocr_document_section_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        if (pageUri == null) {
            Text(
                text = stringResource(R.string.ocr_preview_unavailable),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val contentResolver = LocalContext.current.contentResolver
            val previewState by rememberDocumentPreview(
                pageUri,
                imageMetadata,
                contentResolver,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = PageHarborLayout.documentPreviewMinHeight,
                        max = PageHarborLayout.documentPreviewMaxHeight,
                    )
                    .semantics { contentDescription = description },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = previewState) {
                        ManagedDocumentPreviewState.Loading -> Text(
                            text = stringResource(R.string.ocr_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        ManagedDocumentPreviewState.Unavailable -> Text(
                            text = stringResource(R.string.ocr_preview_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        is ManagedDocumentPreviewState.Ready -> Image(
                            modifier = Modifier.fillMaxSize(),
                            bitmap = state.owner.bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDocumentPreview(
    pageUri: Uri,
    imageMetadata: DocumentImageMetadata,
    contentResolver: android.content.ContentResolver,
) = rememberManagedDocumentPreview(
    requestKey = pageUri to imageMetadata,
    decode = { decodeDocumentPreview(contentResolver, pageUri, imageMetadata) },
)

private fun decodeDocumentPreview(
    contentResolver: android.content.ContentResolver,
    pageUri: Uri,
    imageMetadata: DocumentImageMetadata,
): Bitmap? {
    if (DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(imageMetadata) != null) {
        return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        contentResolver.openInputStream(pageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
    }.getOrNull()
    if (
        DEFAULT_DOCUMENT_INPUT_LIMITS.imageConstraintViolation(
            imageMetadata.copy(width = bounds.outWidth, height = bounds.outHeight),
        ) != null
    ) {
        return null
    }
    val sampleSize = DocumentPreviewDecodePolicy.calculateInSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
    ) ?: return null

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return runCatching {
        contentResolver.openInputStream(pageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }.getOrNull()
}

@Composable
private fun OcrActions(
    copyPayload: String?,
    onCopyText: (String) -> Unit,
    onRecognizeAgain: () -> Unit,
    onClearRecognizedText: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PageHarborSpacing.small)) {
        if (copyPayload != null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onCopyText(copyPayload) },
            ) { Text(stringResource(R.string.ocr_copy_action)) }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRecognizeAgain,
        ) { Text(stringResource(R.string.ocr_recognize_again_action)) }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClearRecognizedText,
        ) { Text(stringResource(R.string.ocr_clear_action)) }
    }
}
