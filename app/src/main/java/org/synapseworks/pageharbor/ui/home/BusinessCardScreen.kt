package org.synapseworks.pageharbor.ui.home

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.synapseworks.pageharbor.businesscard.*
import org.synapseworks.pageharbor.document.capture.CameraAccess

@Composable internal fun BusinessCardScreen(model: CardViewModel, chooseImage: (String, Long) -> Unit,
    allowCamera: (String, Long) -> Unit, openEditor: (String, Long) -> Unit) {
    val s = model.state ?: return
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember(s.owner, s.epoch) { mutableStateOf(CameraAccess.allowed(context)) }
    var showSource by remember(s.owner, s.epoch) { mutableStateOf(false) }
    var showCandidates by remember(s.owner, s.epoch) { mutableStateOf(false) }
    var candidateIndex by remember(s.owner, s.epoch) { mutableIntStateOf(0) }
    DisposableEffect(s.owner, s.epoch, lifecycle) {
        var watching = true
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) allowed = CameraAccess.allowed(context) }
        lifecycle.lifecycle.addObserver(observer)
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        val listener = android.app.AppOpsManager.OnOpChangedListener { op, pkg ->
            if((op == null || op == android.app.AppOpsManager.OPSTR_CAMERA) && (pkg == null || pkg == context.packageName))
                ContextCompat.getMainExecutor(context).execute { if(watching) { allowed = CameraAccess.allowed(context); if(!allowed) model.pause(s.owner, s.epoch) } }
        }
        try { ops?.startWatchingMode(android.app.AppOpsManager.OPSTR_CAMERA, context.packageName, listener) }
        catch(_: SecurityException) { allowed = false; model.pause(s.owner, s.epoch) }
        onDispose { watching = false; lifecycle.lifecycle.removeObserver(observer); runCatching { ops?.stopWatchingMode(listener) } }
    }
    BackHandler { if(s.phase == CardPhase.REVIEW) model.correct() else model.close() }
    Surface(Modifier.fillMaxSize().testTag("card-screen")) {
        if (allowed && (s.phase == CardPhase.CAMERA || s.phase == CardPhase.CAPTURING)) {
            SpecialistCamera(
                tool = "card", title = "Scan business card", shutterLabel = "Capture card", manualLabel = "Enter fields manually",
                owner = s.owner, epoch = s.epoch, capturing = s.phase == CardPhase.CAPTURING,
                begin = { model.beginCapture(s.owner, s.epoch) }, captured = model::captured, failed = model::captureFailed,
                unavailable = { model.pause(s.owner, s.epoch) }, chooseImage = { chooseImage(s.owner, s.epoch) },
                manual = model::manual, close = model::close,
            )
            return@Surface
        }
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("card-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "card-item-1") { Text("Scan business card", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item(key = "card-item-2") { Text("On-device Latin text recognition suggests fields; it can make mistakes. Review spelling and every value. Card text and links never run actions. Images, text and drafts stay temporary.") }
            when(s.phase) {
                CardPhase.CAMERA, CardPhase.CAPTURING -> {
                    item(key = "card-item-4") { RmePrimaryButton(onClick = { allowCamera(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("card-permission")) { Text("Allow camera") } }
                }
                CardPhase.PAUSED -> item(key = "card-item-6") { RmePrimaryButton(onClick = model::reset, modifier = Modifier.fillMaxWidth().testTag("card-retry")) { Text("Resume camera") } }
                CardPhase.PICKER -> item(key = "card-item-7") { Text("Waiting for image selection…", Modifier.testTag("card-waiting")) }
                CardPhase.OCR -> item(key = "card-item-8") { Text("Recognizing card…", Modifier.testTag("card-working").semantics { liveRegion = LiveRegionMode.Polite }) }
                CardPhase.EDIT -> {
                    item(key = "card-item-9") { Text("Correct contact fields", style = MaterialTheme.typography.titleMedium) }
                    item(key = "card-item-10") { Text("Blank fields are omitted. Names, companies and titles are not guessed from ambiguous lines. Up to 8 phones, 8 emails and 4 addresses; no number or spelling normalization. Clear or remove anything you do not want to send.") }
                    item(key = "card-item-11") { CardField("Display name", "card-name", s.draft.name) { model.edit(s.owner, s.epoch, ContactField.NAME, 0, it) } }
                    item(key = "card-item-12") { CardField("Organization", "card-organization", s.draft.organization) { model.edit(s.owner, s.epoch, ContactField.ORGANIZATION, 0, it) } }
                    item(key = "card-item-13") { CardField("Job title", "card-title", s.draft.title) { model.edit(s.owner, s.epoch, ContactField.TITLE, 0, it) } }
                    for((field, label, values) in listOf(Triple(ContactField.PHONE, "Phone", s.draft.phones), Triple(ContactField.EMAIL, "Email", s.draft.emails), Triple(ContactField.ADDRESS, "Address", s.draft.addresses))) {
                        values.forEachIndexed { index, value ->
                            item(key = "field-${field.name}-$index") { CardField("$label ${index + 1}", "card-${field.name.lowercase()}-$index", value, field == ContactField.ADDRESS) { model.edit(s.owner, s.epoch, field, index, it) } }
                            item(key = "remove-${field.name}-$index") { RmeTextButton(onClick = { model.remove(field, index) }, modifier = Modifier.testTag("card-remove-${field.name.lowercase()}-$index")) { Text("Remove $label ${index + 1}") } }
                        }
                        item(key = "add-${field.name}") { RmeSecondaryButton(onClick = { model.add(field) }, enabled = values.size < if(field == ContactField.ADDRESS) 4 else 8,
                            modifier = Modifier.fillMaxWidth().testTag("card-add-${field.name.lowercase()}")) { Text("Add $label") } }
                    }
                    val candidates = s.suggestions?.candidates.orEmpty()
                    if(candidates.isNotEmpty()) {
                        item(key = "card-item-14") { RmeSecondaryButton(onClick = { showCandidates = !showCandidates }, modifier = Modifier.fillMaxWidth().testTag("card-candidates")) { Text("Review other lines (${candidates.size})") } }
                        if(showCandidates) {
                            val index = candidateIndex.coerceIn(candidates.indices)
                            item(key = "card-item-15") { Text(cardDisplay(candidates[index]), Modifier.testTag("card-candidate-value")) }
                            item(key = "card-item-16") { RmeTextButton(onClick = { candidateIndex = (index + 1) % candidates.size }, modifier = Modifier.testTag("card-next-candidate")) { Text("Next candidate (${index + 1}/${candidates.size})") } }
                            for((field, label) in listOf(ContactField.NAME to "display name", ContactField.ORGANIZATION to "organization", ContactField.TITLE to "job title", ContactField.ADDRESS to "address"))
                                item(key = "candidate-${field.name}") { RmeSecondaryButton(onClick = { model.candidate(s.owner, s.epoch, field, candidates[index]) }, modifier = Modifier.fillMaxWidth().testTag("card-use-${field.name.lowercase()}")) { Text("Use candidate as $label") } }
                        }
                    }
                    s.suggestions?.let { suggestions ->
                        item(key = "card-item-17") { RmeTextButton(onClick = { showSource = !showSource }, modifier = Modifier.testTag("card-source-toggle")) { Text("Review recognized source text") } }
                        if(showSource) item(key = "card-item-18") { Text(cardDisplay(suggestions.source), Modifier.testTag("card-source")) }
                    }
                    item(key = "card-item-19") { RmePrimaryButton(onClick = { model.review(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("card-review")) { Text("Review contact") } }
                }
                CardPhase.REVIEW -> {
                    item(key = "card-item-20") { Text("Review outgoing values", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("card-reviewed")) }
                    for((valueIndex, entry) in (listOf("Display name" to s.draft.name, "Organization" to s.draft.organization, "Job title" to s.draft.title) +
                        s.draft.phones.map { "Phone" to it } + s.draft.emails.map { "Email" to it } + s.draft.addresses.map { "Address" to it }).withIndex())
                        if(entry.second.isNotBlank()) item(key = "review-value-$valueIndex") { Text("${entry.first}:\n${entry.second}", Modifier.testTag("card-reviewed-value")) }
                    item(key = "card-item-21") { Text("Only these values go to the contact editor. Check or change the destination account there before saving: the Contacts app may remember an account that syncs externally. You can cancel there. RME cannot verify whether a contact was saved.") }
                    item(key = "card-item-22") { RmePrimaryButton(onClick = { openEditor(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("card-open-editor")) { Text("Open contact editor") } }
                    item(key = "card-item-23") { RmeSecondaryButton(onClick = model::correct, modifier = Modifier.fillMaxWidth().testTag("card-correct")) { Text("Correct fields") } }
                }
                CardPhase.EDITOR -> item(key = "card-item-24") { Text("Contact editor opened. Finish or cancel there; RME has cleared its card draft.", Modifier.testTag("card-editor-opened")) }
                CardPhase.RETURNED -> item(key = "card-item-25") { Text("Contact editor finished or could not open. No saved-contact confirmation is available.", Modifier.testTag("card-editor-returned")) }
            }
            s.problem?.let { problem -> item(key = "card-item-26") { Text(problem, Modifier.testTag("card-error"), color = MaterialTheme.colorScheme.error) } }
            if(s.phase in setOf(CardPhase.CAMERA, CardPhase.PAUSED)) item(key = "card-item-27") { RmeSecondaryButton(onClick = model::manual, modifier = Modifier.fillMaxWidth().testTag("card-manual")) { Text("Enter fields manually") } }
            if(s.phase !in setOf(CardPhase.CAPTURING, CardPhase.OCR, CardPhase.PICKER, CardPhase.EDITOR)) item(key = "card-item-28") {
                RmeSecondaryButton(onClick = { chooseImage(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("card-image")) { Text("Choose card image") }
            }
            if(s.phase in setOf(CardPhase.EDIT, CardPhase.REVIEW, CardPhase.RETURNED)) item(key = "card-item-29") { RmeSecondaryButton(onClick = model::reset, modifier = Modifier.fillMaxWidth().testTag("card-recapture")) { Text("Discard and scan again") } }
            item(key = "card-item-30") { RmeTextButton(onClick = model::close, modifier = Modifier.fillMaxWidth().testTag("card-close")) { Text("Back to library") } }
        }
    }
}

@Composable private fun CardField(label: String, tag: String, value: String, multiline: Boolean = false, change: (String) -> Unit) {
    RmeTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = !multiline,
        modifier = Modifier.fillMaxWidth().testTag(tag), maxLines = if(multiline) 6 else 1)
}
