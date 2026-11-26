package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmeIconButton
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.capture.CapturedPage
import org.synapseworks.pageharbor.image.BitmapDisplaySize
import org.synapseworks.pageharbor.image.decodeDisplayBitmap

@Composable
internal fun CapturePageGrid(
    pages: List<CapturedPage>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onMoveTo: (Int) -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
) {
    var moveOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RmeIconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.capture_grid_close))
                    }
                    Text(stringResource(R.string.capture_page_grid), style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    stringResource(R.string.ocr_page_indicator, selectedIndex + 1, pages.size),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    state = grid,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(pages, key = { _, page -> page.file.name }) { index, page ->
                        CaptureThumbnail(page, index + 1, index == selectedIndex) { onSelected(index) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RmeTextButton(onClick = { moveOpen = true }, enabled = pages.size > 1,
                        modifier = Modifier.weight(1f).testTag("capture-grid-move")) {
                        Text(stringResource(R.string.capture_grid_move_page), textAlign = TextAlign.Center)
                    }
                    RmeTextButton(onClick = onRetake, modifier = Modifier.weight(1f).testTag("capture-grid-retake")) {
                        Text(stringResource(R.string.continuous_retake), textAlign = TextAlign.Center)
                    }
                }
                RmePrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(stringResource(R.string.capture_grid_view_page))
                }
            }
        }
    }
    if (moveOpen) {
        var destination by remember(selectedIndex) { mutableStateOf("") }
        val target = destination.toIntOrNull()?.minus(1)
        AlertDialog(
            onDismissRequest = { moveOpen = false },
            title = { Text(stringResource(R.string.capture_grid_move_page)) },
            text = {
                RmeTextField(
                    value = destination,
                    onValueChange = { destination = it.filter { character -> character in '0'..'9' }.take(5) },
                    label = { Text(stringResource(R.string.capture_grid_target_position, pages.size)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                RmeTextButton(
                    onClick = {
                        val position = requireNotNull(target)
                        onMoveTo(position)
                        scope.launch { grid.animateScrollToItem(position) }
                        moveOpen = false
                    },
                    enabled = target != null && target in pages.indices && target != selectedIndex,
                ) { Text(stringResource(R.string.capture_grid_move_confirm)) }
            },
            dismissButton = {
                RmeTextButton(onClick = { moveOpen = false }) { Text(stringResource(R.string.manual_cancel)) }
            },
        )
    }
}

@Composable
private fun CaptureThumbnail(page: CapturedPage, number: Int, active: Boolean, onClick: () -> Unit) {
    val preview by rememberManagedDocumentPreview(
        requestKey = page.file,
        decode = { decodeDisplayBitmap({ page.file.inputStream() }, BitmapDisplaySize(192, 192), 256_000L) },
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .testTag("capture-grid-page-$number"),
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(.9f)) {
                val image = preview as? ManagedDocumentPreviewState.Ready
                if (image != null) {
                    Image(
                        image.owner.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(stringResource(R.string.ocr_preview_page_heading, number), style = MaterialTheme.typography.labelMedium)
        }
    }
}
