package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.toAndroidUri
import org.synapseworks.pageharbor.image.ManualImageAdjustments

/** Only small draft integers are saveable. Original sources/bitmaps are never put in a Bundle. */
@Composable
internal fun ManualAdjustmentsDialog(
    page: DocumentPage,
    onDismiss: () -> Unit,
    onApply: (ManualImageAdjustments) -> Unit,
) {
    var brightness by rememberSaveable(page.id.value, page.source, page.filter, page.rotation, page.adjustments) {
        mutableIntStateOf(page.adjustments.brightness)
    }
    var contrast by rememberSaveable(page.id.value, page.source, page.filter, page.rotation, page.adjustments) {
        mutableIntStateOf(page.adjustments.contrast)
    }
    val draft = ManualImageAdjustments(brightness, contrast)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.safeDrawingPadding().imePadding().padding(12.dp).widthIn(max = 640.dp)
            .fillMaxWidth().fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manual_adjust_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.manual_adjust_current_page), style = MaterialTheme.typography.bodySmall)
                FilteredDocumentPreview(
                    request = FilteredPreviewRequest(page.id.value, page.source.reference, page.filter,
                        page.rotation, page.imageMetadata, draft),
                    pageUri = page.source.toAndroidUri(), pageNumber = 1, pageCount = 1,
                    minHeight = 100.dp, maxHeight = 200.dp,
                )
                AdjustmentSlider(R.string.manual_brightness, "manual-brightness", brightness) { brightness = it }
                AdjustmentSlider(R.string.manual_contrast, "manual-contrast", contrast) { contrast = it }
                Text(stringResource(R.string.manual_presets), style = MaterialTheme.typography.labelLarge)
                RmeSecondaryButton(onClick = { brightness = 20; contrast = 0 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manual_preset_brighter))
                }
                RmeSecondaryButton(onClick = { brightness = 0; contrast = 25 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.manual_preset_contrast))
                }
                RmeSecondaryButton(onClick = { brightness = 0; contrast = 0 }, modifier = Modifier.fillMaxWidth().testTag("manual-reset")) {
                    Text(stringResource(R.string.manual_reset))
                }
                RmePrimaryButton(onClick = { onApply(draft) }, modifier = Modifier.fillMaxWidth().testTag("manual-apply")) {
                    Text(stringResource(R.string.manual_apply))
                }
                RmeTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("manual-cancel")) {
                    Text(stringResource(R.string.manual_cancel))
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(label: Int, tag: String, value: Int, onChange: (Int) -> Unit) {
    val title = stringResource(label)
    Text(stringResource(R.string.manual_value, title, value))
    Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(-100, 100)) },
        valueRange = -100f..100f, steps = 199,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
            contentDescription = title
            stateDescription = "$value %"
        })
}
