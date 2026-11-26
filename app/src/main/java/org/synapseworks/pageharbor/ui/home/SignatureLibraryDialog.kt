package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.synapseworks.pageharbor.document.annotation.AnnotationEditorViewModel
import org.synapseworks.pageharbor.ui.signature.SignaturePad

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SignatureLibraryDialog(editor: AnnotationEditorViewModel) {
    val workspace = editor.signatures
    val draft = workspace.state ?: return
    Dialog(onDismissRequest = workspace::cancel,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        // The dialog owns a distinct Compose focus/input root from the page editor.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun finishName() { focus.clearFocus(); keyboard?.hide() }
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("signature-library")) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Handwritten signatures",style = MaterialTheme.typography.titleLarge)
                    Text("Draw with a finger or stylus. This places handwriting; it does not verify identity or certify document integrity.")
                    SignaturePad(draft.strokes,{ workspace.strokes(it,draft.ratio) },enabled = !draft.busy,aspectRatio = draft.ratio,strokeWidthFraction = draft.strokeWidth,
                        onRejected = { workspace.error("Drawing limit reached. The whole stroke was rejected; undo or draw a shorter stroke.") },modifier = Modifier.testTag("signature-pad"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RmeSecondaryButton(onClick = workspace::undoStroke,enabled = !draft.busy && draft.strokes.isNotEmpty(),modifier = Modifier.testTag("signature-undo-stroke")) { Text("Undo stroke") }
                        RmeSecondaryButton(onClick = workspace::reset,enabled = !draft.busy,modifier = Modifier.testTag("signature-reset")) { Text("New / clear drawing") }
                    }
                    RmeTextField(draft.name,workspace::name,enabled = !draft.busy,label = { Text("Saved signature name") },modifier = Modifier.fillMaxWidth().testTag("signature-name"),singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),keyboardActions = KeyboardActions(onDone = { finishName() }))
                    RmeTextButton(onClick = { finishName() },modifier = Modifier.testTag("signature-hide-keyboard")) { Text("Hide keyboard") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RmePrimaryButton(onClick = { workspace.save(false) },enabled = !draft.busy && draft.strokes.isNotEmpty(),modifier = Modifier.testTag("signature-save")) { Text("Save new template") }
                        if (draft.selected != null) {
                            RmeSecondaryButton(onClick = { workspace.save(true) },enabled = !draft.busy,modifier = Modifier.testTag("signature-replace")) { Text("Replace saved drawing") }
                            RmeSecondaryButton(onClick = workspace::rename,enabled = !draft.busy,modifier = Modifier.testTag("signature-rename")) { Text("Rename saved template") }
                            RmeSecondaryButton(onClick = workspace::delete,contentColor = MaterialTheme.colorScheme.error,enabled = !draft.busy,modifier = Modifier.testTag("signature-delete-template")) { Text("Delete saved template") }
                        }
                    }
                    Text("Use drawing previews a grouped mark on the page. Apply in the page editor commits the placement; page Cancel discards it.",style = MaterialTheme.typography.bodySmall)
                    Text("Templates stay on this device and are excluded from document and automatic backups. Saving is explicit. Cancel does not undo a completed template save. Deleting a template leaves placed copies in documents and exported files.",style = MaterialTheme.typography.bodySmall)
                    Text("Saved signatures (${draft.templates.size}/32)",style = MaterialTheme.typography.titleMedium)
                    draft.templates.forEachIndexed { index,template ->
                        RmeSecondaryButton(onClick = { workspace.select(template) },enabled = !draft.busy,modifier = Modifier.testTag("signature-template-$index")) { Text(template.name) }
                    }
                    if (draft.busy) Text("Updating signature library…")
                    draft.error?.let { Text(it,color = MaterialTheme.colorScheme.error,modifier = Modifier.testTag("signature-error")) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RmeTextButton(onClick = workspace::cancel,modifier = Modifier.testTag("signature-cancel")) { Text("Cancel") }
                    RmePrimaryButton(onClick = editor::useSignature,enabled = !draft.busy && draft.strokes.isNotEmpty(),modifier = Modifier.testTag("signature-use")) { Text("Use drawing") }
                }
            }
        }
    }
}
