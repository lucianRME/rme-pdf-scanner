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
import org.synapseworks.pageharbor.receipt.*
import org.synapseworks.pageharbor.document.capture.CameraAccess

@Composable internal fun ReceiptScreen(model: ReceiptViewModel, chooseImage: (String, Long) -> Unit,
    allowCamera: (String, Long) -> Unit, workbook:(()->Unit)?=null, dismiss:()->Unit={model.close()}) {
    val s = model.state ?: return
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var allowed by remember(s.owner, s.epoch) { mutableStateOf(CameraAccess.allowed(context)) }
    var showSource by remember(s.owner, s.epoch) { mutableStateOf(false) }
    var evidenceField by remember(s.owner,s.epoch) { mutableStateOf<ReceiptField?>(null) }
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
    BackHandler { if(s.phase == ReceiptPhase.REVIEW) model.correct() else dismiss() }
    Surface(Modifier.fillMaxSize().testTag("receipt-screen")) {
        if (allowed && (s.phase == ReceiptPhase.CAMERA || s.phase == ReceiptPhase.CAPTURING)) {
            SpecialistCamera(
                tool = "receipt", title = "Scan receipt", shutterLabel = "Capture receipt", manualLabel = "Enter fields manually",
                owner = s.owner, epoch = s.epoch, capturing = s.phase == ReceiptPhase.CAPTURING,
                begin = { model.beginCapture(s.owner, s.epoch) }, captured = model::captured, failed = model::captureFailed,
                unavailable = { model.pause(s.owner, s.epoch) }, chooseImage = { chooseImage(s.owner, s.epoch) },
                manual = model::manual, close = dismiss,
            )
            return@Surface
        }
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp).testTag("receipt-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "receipt-item-1") { Text("Extract receipt", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
            item(key = "receipt-item-2") { Text("On-device Latin OCR suggests receipt fields. Verify every value against the printed receipt. Uncertain and missing values need review. Text stays inert; images, OCR and drafts stay temporary.") }
            when(s.phase) {
                ReceiptPhase.CAMERA, ReceiptPhase.CAPTURING -> {
                    item(key = "receipt-item-4") { RmePrimaryButton(onClick = { allowCamera(s.owner, s.epoch) }, modifier = Modifier.fillMaxWidth().testTag("receipt-permission")) { Text("Allow camera") } }
                }
                ReceiptPhase.PAUSED -> item(key = "receipt-item-6") { RmePrimaryButton(onClick = model::reset, modifier = Modifier.fillMaxWidth().testTag("receipt-retry")) { Text("Resume camera") } }
                ReceiptPhase.PICKER -> item(key = "receipt-item-7") { Text("Waiting for image selection…", Modifier.testTag("receipt-waiting")) }
                ReceiptPhase.OCR -> item(key = "receipt-item-8") { Text("Recognizing receipt…", Modifier.testTag("receipt-working").semantics { liveRegion = LiveRegionMode.Polite }) }
                ReceiptPhase.EDIT, ReceiptPhase.REVIEW -> {
                    val reviewing = s.phase == ReceiptPhase.REVIEW
                    item(key="formats") {
                        Text("Dates: strict YYYY-MM-DD or slash day/month/year. AUTO leaves ambiguous slash dates blank. Amounts: up to 9 integral and exactly 2 decimal digits (integers accepted); no rounding. Currencies: EUR/USD/GBP/CAD/AUD/RON/CHF; bare $ is uncertain.")
                    }
                    if(!reviewing) {
                        item(key="date-rule") { RmeSecondaryButton(onClick={ model.rules(s.owner,s.epoch,s.rules.copy(dates=ReceiptDateOrder.entries[(s.rules.dates.ordinal+1)%3])) },modifier=Modifier.fillMaxWidth().testTag("receipt-date-rule")) { Text("Date order: ${s.rules.dates} (change)") } }
                        item(key="decimal-rule") { RmeSecondaryButton(onClick={ model.rules(s.owner,s.epoch,s.rules.copy(decimals=ReceiptDecimal.entries[(s.rules.decimals.ordinal+1)%3])) },modifier=Modifier.fillMaxWidth().testTag("receipt-decimal-rule")) { Text("Decimal separator: ${s.rules.decimals} (change)") } }
                    } else item(key="review-heading") { Text("Reviewed receipt — temporary",Modifier.testTag("receipt-reviewed"),style=MaterialTheme.typography.titleMedium) }
                    for(field in ReceiptField.entries) {
                        val raw = s.draft.values[field].orEmpty()
                        val normalized = ReceiptValues.normalize(field,raw,s.rules)
                        val suggestion = s.extraction?.fields?.get(field)
                        item(key="value-${field.name}") {
                            if(reviewing) Text("${field.label}: ${s.reviewed?.get(field) ?: "Missing / not supplied"}",Modifier.testTag("receipt-reviewed-${field.name.lowercase()}"))
                            else RmeTextField(value=raw,onValueChange={model.edit(s.owner,s.epoch,field,it)},label={Text(field.label)},singleLine=true,
                                modifier=Modifier.fillMaxWidth().testTag("receipt-${field.name.lowercase()}"))
                            Text(if(field in s.draft.corrected) "User corrected — original evidence retained" else suggestion?.status ?: "Missing — enter if known")
                            if(!reviewing && raw.isNotBlank()) Text("Normalized: ${normalized ?: "Uncertain / invalid — correct or clear"}")
                        }
                        val candidates=suggestion?.candidates.orEmpty()
                        if(candidates.isNotEmpty()) {
                            item(key="evidence-button-${field.name}") { RmeTextButton(onClick={ evidenceField=if(evidenceField==field) null else field },modifier=Modifier.testTag("receipt-evidence-${field.name.lowercase()}")) { Text("${field.label} source evidence (${candidates.size})") } }
                            if(evidenceField==field) candidates.forEachIndexed { index,candidate -> item(key="evidence-${field.name}-$index") {
                                Text("Source rows ${candidate.rows.flatMap { it.indices }.joinToString { (it+1).toString() }}: " + cardDisplay(candidate.rows.joinToString("\n") { it.text }))
                                Text("Original: ${cardDisplay(candidate.raw)}; normalized suggestion: ${candidate.normalized ?: "Uncertain"}")
                            } }
                        }
                    }
                    val values = if(reviewing) s.reviewed else s.draft.normalized(s.rules)
                    values?.let { ReceiptValues.warnings(it) }?.forEachIndexed { index,warning -> item(key="warning-$index") { Text(warning,Modifier.testTag("receipt-warning"),color=MaterialTheme.colorScheme.error) } }
                    item(key="source-toggle") { RmeTextButton(onClick={showSource=!showSource},modifier=Modifier.testTag("receipt-source-toggle")) { Text("Review original recognized text") } }
                    if(showSource) item(key="source") { Text(cardDisplay(s.extraction?.source.orEmpty()),Modifier.testTag("receipt-source")) }
                    if(reviewing) {
                        item(key="review-notice") { Text("Check missing fields, source evidence and corrections. This reviewed result stays temporary. No original image is saved here. A separate reviewed workbook export is available. Back discards this receipt review.") }
                        if(workbook!=null)item(key="workbook"){RmePrimaryButton(onClick=workbook,modifier=Modifier.fillMaxWidth().testTag("receipt-workbook")){Text("Export reviewed workbook")}}
                        item(key="correct") { RmeSecondaryButton(onClick=model::correct,modifier=Modifier.fillMaxWidth().testTag("receipt-correct")) { Text("Correct fields") } }
                    } else item(key="review") { RmePrimaryButton(onClick={model.review(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("receipt-review")) { Text("Review receipt") } }
                }
            }
            s.problem?.let { problem -> item(key="problem") { Text(problem,Modifier.testTag("receipt-error"),color=MaterialTheme.colorScheme.error) } }
            if(s.phase in setOf(ReceiptPhase.CAMERA,ReceiptPhase.PAUSED)) item(key="manual") { RmeSecondaryButton(onClick=model::manual,modifier=Modifier.fillMaxWidth().testTag("receipt-manual")) { Text("Enter fields manually") } }
            if(s.phase !in setOf(ReceiptPhase.CAPTURING,ReceiptPhase.OCR,ReceiptPhase.PICKER)) item(key="image") { RmeSecondaryButton(onClick={chooseImage(s.owner,s.epoch)},modifier=Modifier.fillMaxWidth().testTag("receipt-image")) { Text("Choose receipt image") } }
            if(s.phase in setOf(ReceiptPhase.EDIT,ReceiptPhase.REVIEW)) item(key="rescan") { RmeSecondaryButton(onClick=model::reset,modifier=Modifier.fillMaxWidth().testTag("receipt-recapture")) { Text("Discard and scan again") } }
            item(key="close") { RmeTextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth().testTag("receipt-close")) { Text("Back to library") } }
        }
    }
}
