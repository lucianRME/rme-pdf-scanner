package org.synapseworks.pageharbor.ui.localtools

import org.synapseworks.pageharbor.ui.theme.RmeFilterChip
import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import org.synapseworks.pageharbor.image.renderDisplayBitmap
import org.synapseworks.pageharbor.ui.home.rememberManagedDocumentPreview
import org.synapseworks.pageharbor.ui.home.ManagedDocumentPreviewState
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.booklayout.BookLayoutMode
import org.synapseworks.pageharbor.document.booklayout.BookLayoutSettings
import org.synapseworks.pageharbor.document.booklayout.BookPaperSize
import org.synapseworks.pageharbor.document.booklayout.BookReadingOrder
import org.synapseworks.pageharbor.document.localtools.LocalPdfToolRequest
import org.synapseworks.pageharbor.document.localtools.LocalPdfToolsState
import org.synapseworks.pageharbor.document.protectedpdf.isSupportedPdfPassword
import org.synapseworks.pageharbor.document.signature.HandwrittenSignature
import org.synapseworks.pageharbor.document.signature.SignaturePdfRequest
import org.synapseworks.pageharbor.document.signature.SignaturePlacement
import org.synapseworks.pageharbor.document.signature.SignatureStroke
import org.synapseworks.pageharbor.ui.signature.SignaturePad

private enum class ToolMode(val label: String) {
    SIGNATURE("Signature"), BOOK("Book pages"), TWO_UP("Two pages per sheet"), PASSWORD("Protect PDF"),
}

@Composable
fun LocalPdfToolsDialog(
    state: LocalPdfToolsState,
    onDismiss: () -> Unit,
    onOptionsChanged: () -> Unit,
    onPrepare: (LocalPdfToolRequest) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPreview: () -> Unit,
    onIdCopy: () -> Unit,
) {
    if (!state.visible) return
    var mode by remember(state.source) { mutableStateOf(ToolMode.SIGNATURE) }
    var strokes by remember(state.source) { mutableStateOf(emptyList<SignatureStroke>()) }
    var pageText by remember(state.source) { mutableStateOf("1") }
    var left by remember(state.source) { mutableStateOf(0.55f) }
    var top by remember(state.source) { mutableStateOf(0.80f) }
    var width by remember(state.source) { mutableStateOf(0.35f) }
    var height by remember(state.source) { mutableStateOf(0.10f) }
    var split by remember(state.source) { mutableStateOf(0.5f) }
    var order by remember(state.source) { mutableStateOf(BookReadingOrder.LEFT_TO_RIGHT) }
    var paper by remember(state.source) { mutableStateOf(BookPaperSize.A4) }
    // Passwords and handwriting are never saved through rememberSaveable or to a preferences file.
    var password by remember(state.source) { mutableStateOf("") }
    var confirmation by remember(state.source) { mutableStateOf("") }
    LaunchedEffect(state.output) {
        if (state.output != null) { password = ""; confirmation = "" }
    }
    val pageIndex = pageText.toIntOrNull()?.minus(1)
    val placement = SignaturePlacement(left, top, (left + width).coerceAtMost(1f), (top + height).coerceAtMost(1f))
    val enabled = !state.busy && state.source != null
    val valid = enabled && when (mode) {
        ToolMode.SIGNATURE -> strokes.isNotEmpty() && pageIndex != null && pageIndex in 0 until state.sourcePages
        ToolMode.PASSWORD -> password == confirmation && isSupportedPdfPassword(password.toCharArray())
        else -> true
    }
    Dialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(12.dp).widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(0.95f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Local document tools", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("Create a separate PDF copy. Processing stays on this device.", style = MaterialTheme.typography.bodyMedium)
                if (state.busy) CircularProgressIndicator()
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (state.source != null) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolMode.entries.forEach { tool ->
                            RmeFilterChip(selected = mode == tool, onClick = { onOptionsChanged(); mode = tool }, enabled = enabled, label = { Text(tool.label) })
                        }
                    }
                    when (mode) {
                        ToolMode.SIGNATURE -> {
                            Text("Draw your signature, then choose its page and position.")
                            RmeTextField(
                                value = pageText, onValueChange = { onOptionsChanged(); pageText = it.take(6) },
                                label = { Text("Page (1–${state.sourcePages})") }, enabled = enabled, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = pageIndex == null || pageIndex !in 0 until state.sourcePages,
                            )
                            PdfToolPagePreview(state.source, (pageIndex ?: 0).coerceIn(0, state.sourcePages - 1), strokes, placement)
                            SignaturePad(strokes, { onOptionsChanged(); strokes = it }, Modifier.fillMaxWidth().height(160.dp), enabled)
                            RmeTextButton(onClick = { onOptionsChanged(); strokes = emptyList() }, enabled = enabled) { Text("Clear handwriting") }
                            ToolSlider("Signature width", width, 0.1f..0.8f, enabled) { onOptionsChanged(); width = it; left = left.coerceAtMost(1f - it) }
                            ToolSlider("Signature height", height, 0.04f..0.4f, enabled) { onOptionsChanged(); height = it; top = top.coerceAtMost(1f - it) }
                            ToolSlider("Horizontal position", left, 0f..(1f - width), enabled) { onOptionsChanged(); left = it }
                            ToolSlider("Vertical position", top, 0f..(1f - height), enabled) { onOptionsChanged(); top = it }
                        }
                        ToolMode.BOOK -> {
                            Text("Split every open-book spread into two pages. Adjust the gutter and choose the reading order.")
                            PdfToolPagePreview(state.source, 0, split = split)
                            ToolSlider("Gutter position", split, 0.1f..0.9f, enabled) { onOptionsChanged(); split = it }
                            ReadingOrderChoices(order, enabled) { onOptionsChanged(); order = it }
                            Text("This separates the page layout; it does not flatten curved paper.", style = MaterialTheme.typography.bodySmall)
                        }
                        ToolMode.TWO_UP -> {
                            Text("Place pairs of pages on landscape sheets. An odd final page keeps its own sheet.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BookPaperSize.entries.forEach { size ->
                                    RmeFilterChip(selected = paper == size, onClick = { onOptionsChanged(); paper = size }, enabled = enabled, label = { Text(if (size == BookPaperSize.A4) "A4" else "Letter") })
                                }
                            }
                            ReadingOrderChoices(order, enabled) { onOptionsChanged(); order = it }
                        }
                        ToolMode.PASSWORD -> {
                            Text("Recipients enter a password to open the PDF. Share the file through Android and send its password separately.")
                            RmeTextField(
                                value = password, onValueChange = { onOptionsChanged(); password = it }, label = { Text("Opening password") },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            RmeTextField(
                                value = confirmation, onValueChange = { onOptionsChanged(); confirmation = it }, label = { Text("Confirm password") },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            Text("Use at least 8 characters, up to 127 UTF-8 bytes.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RmePrimaryButton(
                        enabled = valid,
                        onClick = {
                            val request = when (mode) {
                                ToolMode.SIGNATURE -> LocalPdfToolRequest.Signature(SignaturePdfRequest(pageIndex!!, HandwrittenSignature(strokes), placement))
                                ToolMode.BOOK -> LocalPdfToolRequest.Book(BookLayoutSettings(BookLayoutMode.SPLIT_SPREAD, split, order, paper))
                                ToolMode.TWO_UP -> LocalPdfToolRequest.Book(BookLayoutSettings(BookLayoutMode.TWO_UP, split, order, paper))
                                ToolMode.PASSWORD -> LocalPdfToolRequest.Protected(password.toCharArray())
                            }
                            onPrepare(request)
                        },
                    ) { Text("Prepare copy") }
                    if (state.output != null) {
                        HorizontalDivider()
                        Text("Copy prepared: ${state.outputPages} pages.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RmeSecondaryButton(onClick = onPreview, enabled = !state.busy) { Text("Preview") }
                            RmePrimaryButton(onClick = onSave, enabled = !state.busy) { Text("Save") }
                            RmeSecondaryButton(onClick = onShare, enabled = !state.busy) { Text("Share") }
                        }
                    }
                    HorizontalDivider()
                    RmeTextButton(onClick = onIdCopy, enabled = enabled) { Text("Two-sided ID card on A4 or Letter") }
                }
                RmeTextButton(onClick = onDismiss, enabled = !state.busy) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ReadingOrderChoices(order: BookReadingOrder, enabled: Boolean, onChange: (BookReadingOrder) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BookReadingOrder.entries.forEach { value ->
            RmeFilterChip(selected = order == value, onClick = { onChange(value) }, enabled = enabled,
                label = { Text(if (value == BookReadingOrder.LEFT_TO_RIGHT) "Left to right" else "Right to left") })
        }
    }
}

@Composable
private fun ToolSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Text("$label: ${(value * 100).roundToInt()}%")
    Slider(value, onChange, enabled = enabled, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}

@Composable
private fun PdfToolPagePreview(
    source: File,
    pageIndex: Int,
    strokes: List<SignatureStroke> = emptyList(),
    placement: SignaturePlacement? = null,
    split: Float? = null,
) {
    val preview by rememberManagedDocumentPreview(
        requestKey = source to pageIndex,
        decode = {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(pageIndex).use { page ->
                        val scale = 900f / maxOf(page.width, page.height)
                        renderDisplayBitmap(maxOf(1, (page.width * scale).roundToInt()), maxOf(1, (page.height * scale).roundToInt())) { bitmap ->
                            bitmap.eraseColor(AndroidColor.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        },
    )
    (preview as? ManagedDocumentPreviewState.Ready)?.owner?.bitmap?.let { page ->
        Box(Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height)) {
            Image(page.asImageBitmap(), "Page preview", Modifier.fillMaxWidth())
            Canvas(Modifier.matchParentSize()) {
                split?.let { drawLine(Color.Blue, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 2.dp.toPx()) }
                placement?.let { bounds ->
                    drawRect(Color.Blue, Offset(bounds.left * size.width, bounds.top * size.height),
                        androidx.compose.ui.geometry.Size(bounds.width * size.width, bounds.height * size.height), style = Stroke(1.dp.toPx()))
                    strokes.forEach { stroke ->
                        val inkWidth = maxOf(1f, bounds.width * size.width * 0.012f)
                        val path = Path()
                        stroke.points.forEachIndexed { i, point ->
                            val x = (bounds.left + point.x * bounds.width) * size.width
                            val y = (bounds.top + point.y * bounds.height) * size.height
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (stroke.points.size == 1) {
                            val point = stroke.points.single()
                            drawCircle(Color.Black, inkWidth / 2f, Offset(
                                (bounds.left + point.x * bounds.width) * size.width,
                                (bounds.top + point.y * bounds.height) * size.height,
                            ))
                        } else {
                            drawPath(path, Color.Black, style = Stroke(inkWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}
