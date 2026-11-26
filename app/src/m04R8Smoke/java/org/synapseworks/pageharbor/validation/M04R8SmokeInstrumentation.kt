package org.synapseworks.pageharbor.validation

import org.synapseworks.pageharbor.workflow.archivalGenerationChecks
import org.synapseworks.pageharbor.workflow.archivalFaultChecks
import org.synapseworks.pageharbor.workflow.workflowRestartChecks
import org.synapseworks.pageharbor.workflow.workflowSaveChecks

import org.synapseworks.pageharbor.pdftext.visualFirstOffline
import org.synapseworks.pageharbor.pdftext.textFirstOffline
import org.synapseworks.pageharbor.forms.formFirstOffline
import android.app.Activity
import org.synapseworks.pageharbor.pptx.pptxGenerationChecks
import org.synapseworks.pageharbor.pptx.pptxSaveChecks
import org.synapseworks.pageharbor.pptx.pptxReaderChecks
import org.synapseworks.pageharbor.pptx.pptxRecipeChecks
import org.synapseworks.pageharbor.pptx.pptxRestartChecks
import org.synapseworks.pageharbor.xlsx.xlsxRecipeChecks
import org.synapseworks.pageharbor.xlsx.xlsxReaderChecks
import org.synapseworks.pageharbor.xlsx.xlsxRestartChecks
import org.synapseworks.pageharbor.xlsx.xlsxGenerationChecks
import org.synapseworks.pageharbor.xlsx.xlsxSaveChecks
import org.synapseworks.pageharbor.docx.docxGenerationChecks
import org.synapseworks.pageharbor.docx.docxLayoutUiChecks
import org.synapseworks.pageharbor.docx.docxRestartChecks
import org.synapseworks.pageharbor.docx.docxRecipeChecks
import org.synapseworks.pageharbor.docx.docxSaveChecks
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage
import org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import androidx.room.Room
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.synapseworks.pageharbor.document.protectedpdf.PasswordProtectedPdf
import org.synapseworks.pageharbor.document.protectedpdf.PasswordPdfResult
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.backup.format.*
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.ocr.MlKitOcrEngine
import org.synapseworks.pageharbor.ocr.OcrPage
import org.synapseworks.pageharbor.ui.flow.DocumentToolOperation
import org.synapseworks.pageharbor.ui.flow.ToolInvocationContext
import org.synapseworks.pageharbor.ui.flow.ToolInvocationSource

/**
 * Synthetic, local-only smoke checks compiled and optimized with the isolated release variant.
 * Platform instrumentation avoids giving the optimized app blanket test keep rules. This source
 * and its instrumentation manifest are absent from production and normal test variants.
 */
class M04R8SmokeInstrumentation : Instrumentation() {
    private var xlsxRestart=false
    private var pptxRestart=false
    private var pptxOnly=false
    private var xlsxOnly=false
    private var docxOnly=false
    private var docxRecipeOnly=false
    private var docxRestart=false
    private var archivalSaveOnly=false
    private var archivalRestart=false
    private var archivalOnly=false
    private var formOnly=false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        pptxRestart=arguments?.getString("m34Restart")=="true"
        pptxOnly=arguments?.getString("m34Only")=="true"
        xlsxOnly=arguments?.getString("m33Only")=="true"
        xlsxRestart=arguments?.getString("m33Restart")=="true"
        docxRecipeOnly=arguments?.getString("m32RecipeOnly")=="true"
        docxOnly=arguments?.getString("m32Only")=="true"||docxRecipeOnly
        docxRestart=arguments?.getString("m32Restart")=="true"
        bleedOnly=arguments?.getString("m25Only")=="true"
        objectRemovalOnly=arguments?.getString("m28Only")=="true"
        fingerRemovalOnly=arguments?.getString("m29Only")=="true"
        workflowOnly=arguments?.getString("m30Only")=="true"
        workflowRestart=arguments?.getString("m30Restart")=="true"
        moireOnly=arguments?.getString("m27Only")=="true"
        creaseStainOnly=arguments?.getString("m26Only")=="true"
        archivalSaveOnly=arguments?.getString("m31Save")=="true"
        archivalRestart=arguments?.getString("m31Restart")=="true"
        archivalOnly=arguments?.getString("m31Only")=="true"
        formOnly=arguments?.getString("m23Only")=="true"
        textOnly=arguments?.getString("m24Only")=="true"
        visualOnly=arguments?.getString("m24VisualOnly")=="true"
        start()
    }

    private var objectRemovalOnly=false
    private var workflowOnly=false
    private var workflowRestart=false
    private var fingerRemovalOnly=false
    private var moireOnly=false
    private var creaseStainOnly=false
    private var bleedOnly=false
    private var visualOnly=false
    private var textOnly=false
    override fun onStart() {
        val results = Bundle()
        check(targetContext.packageName == "org.synapseworks.pageharbor.m04r8smoke")
        check(!BuildConfig.DEBUG && !BuildConfig.SHOW_BUILD_DETAILS)
        val cases = listOf(
            "archivalRecipes" to { if(archivalRestart)workflowRestartChecks(true) else workflowSaveChecks(true) },
            "archivalCopies" to { archivalGenerationChecks();archivalFaultChecks() },
            "workflowRecipes" to { if(workflowRestart)workflowRestartChecks() else workflowSaveChecks() },
            "objectRemoval" to { objectRemoval() },
            "fingerRemoval" to { fingerRemoval() },
            "reviewedPptx" to { if(pptxRestart)pptxRestartChecks()else{pptxGenerationChecks();pptxSaveChecks();pptxReaderChecks();pptxRecipeChecks()} },
            "reviewedXlsx" to { if(xlsxRestart)xlsxRestartChecks()else{xlsxGenerationChecks();xlsxSaveChecks();xlsxReaderChecks();xlsxRecipeChecks()} },
            "editableDocx" to { if(docxRestart)docxRestartChecks()else if(docxRecipeOnly)docxRecipeChecks()else{docxGenerationChecks();docxSaveChecks();docxLayoutUiChecks();docxRecipeChecks()} },
            "moire" to { moire() },
            "creaseStain" to { creaseStain() },
            "bleedThrough" to { bleedThrough() },
            "visualFirstOffline" to { visualFirstOffline() },
            "textFirstOffline" to { textFirstOffline() },
            "formFirstOffline" to { formFirstOffline() },
            "tableFirstOffline" to ::tableFirstOffline,
            "receiptFirstOffline" to ::receiptFirstOffline,
            "compactEmailAutomatic" to ::compactEmailAutomatic,
            "businessCard" to ::businessCard,
            "qrCode" to ::qrCode,
            "roomReflection" to ::roomReflection,
            "organization" to ::organization,
            "recycle" to ::recycle,
            "toolSerialization" to ::toolSerialization,
            "pdfResources" to ::pdfResources,
            "bundledOcr" to ::bundledOcr,
            "searchablePdf" to ::searchablePdf,
            "backupJson" to ::backupJson,
            "passwordEncryption" to ::passwordEncryption,
            "manualAdjustments" to ::manualAdjustments,
            "selectedPageBatch" to ::selectedPageBatch,
            "shadowGlare" to ::shadowGlare,
            "backgroundWhitening" to ::backgroundWhitening,
            "continuousCapture" to ::continuousCapture,
            "bookSplit" to ::bookSplit,
            "pageCurvature" to ::pageCurvature,
            "annotations" to ::annotations,
            "signatures" to ::signatures,
            "collage" to ::collage,
            "documentHistory" to ::documentHistory,
        )
        var failed = 0
        val selectedCases=cases.filter{(!pptxOnly&&!pptxRestart||it.first=="reviewedPptx")&&(!xlsxOnly&&!xlsxRestart||it.first=="reviewedXlsx")&&(!docxOnly&&!docxRestart||it.first=="editableDocx")&&(!archivalSaveOnly&&!archivalRestart||it.first=="archivalRecipes")&&(!archivalOnly||it.first=="archivalCopies")&&(!workflowOnly&&!workflowRestart||it.first=="workflowRecipes")&&(!fingerRemovalOnly||it.first=="fingerRemoval")&&(!objectRemovalOnly||it.first=="objectRemoval")&&(!moireOnly||it.first=="moire")&&(!creaseStainOnly||it.first=="creaseStain")&&(!bleedOnly||it.first=="bleedThrough")&&(!formOnly||it.first=="formFirstOffline")&&(!textOnly||it.first=="textFirstOffline")&&(!visualOnly||it.first=="visualFirstOffline")}
        selectedCases.forEach { (name, test) ->
            try {
                test()
                results.putString(name, "PASS")
            } catch (failure: Throwable) {
                failed++
                // Never include exception messages, document data or paths in the result.
                results.putString(name, "FAIL:" + failure.javaClass.simpleName + if(name == "workflowRecipes") ":"+org.synapseworks.pageharbor.workflow.workflowSmokeStage else if(name == "fingerRemoval") ":" + fingerRemovalSmokeStage else if(name == "objectRemoval") ":" + objectRemovalSmokeStage else if(name == "moire") ":" + moireSmokeStage else if(name == "creaseStain") ":" + creaseStainSmokeStage else if(name == "bleedThrough") ":" + bleedSmokeStage else if(name == "businessCard") ":" + cardSmokeStage else if(name == "receiptFirstOffline") ":" + receiptSmokeStage else if(name == "tableFirstOffline") ":" + tableSmokeStage else if(name == "visualFirstOffline") ":" + org.synapseworks.pageharbor.pdftext.visualSmokeStage else if(name == "textFirstOffline") ":" + org.synapseworks.pageharbor.pdftext.textSmokeStage else if(name == "formFirstOffline") ":" + org.synapseworks.pageharbor.forms.formSmokeStage else if(name == "reviewedXlsx") ":"+org.synapseworks.pageharbor.xlsx.xlsxSmokeStage else "")
            }
        }
        results.putInt("tests", selectedCases.size)
        results.putInt("failures", failed)
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    /** Frozen known synthetic input only; baseline trace is isolated from production code. */
    private var tableSmokeStage="fixture"
    private fun tableFirstOffline() {
        val app=targetContext.applicationContext as android.app.Application
        val expected=org.synapseworks.pageharbor.table.FrozenTables.optical
        val bytes=org.synapseworks.pageharbor.table.TableTestImage.bytes(expected)
        tableSmokeStage="first-optical-fields"
        val extraction=org.synapseworks.pageharbor.table.TableRecognition.recognize({ByteArrayInputStream(bytes)}) ?: error("Table unavailable")
        org.synapseworks.pageharbor.table.TableOpticalEvidence.record(app,"optimized-upright",extraction.grid,expected)
        check(extraction.grid.values()==expected && extraction.grid.rows.flatten().none{it.corrected})
        check(extraction.units.single{it.id==extraction.grid.rows[1][1].evidence.single()}.text=="BLUE B0OK")
        for(rotation in listOf(90,180,270)) {
            val rotated=org.synapseworks.pageharbor.table.TableTestImage.bytes(expected,rotation)
            val result=checkNotNull(org.synapseworks.pageharbor.table.TableRecognition.recognize({ByteArrayInputStream(rotated)},rotation))
            org.synapseworks.pageharbor.table.TableOpticalEvidence.record(app,"optimized-rotation-$rotation",result.grid,expected)
            check(result.grid.values()==expected && result.grid.rows.flatten().none{it.corrected})
        }
        val literalExpected=org.synapseworks.pageharbor.table.FrozenLiteralTables.grid
        val literalBytes=org.synapseworks.pageharbor.table.TableTestImage.bytes(literalExpected)
        val literalPage=checkNotNull(org.synapseworks.pageharbor.businesscard.CardRecognition.recognizePage({ByteArrayInputStream(literalBytes)}))
        val literalRaw=checkNotNull(org.synapseworks.pageharbor.table.TableParser.parse(literalPage.text,literalPage.layout))
        val literalRefined=checkNotNull(org.synapseworks.pageharbor.table.TableRecognition.recognize({ByteArrayInputStream(literalBytes)}))
        org.synapseworks.pageharbor.table.TableOpticalEvidence.record(app,"optimized-literals-raw",literalRaw.grid,literalExpected)
        org.synapseworks.pageharbor.table.TableOpticalEvidence.record(app,"optimized-literals-refined",literalRefined.grid,literalExpected)
        for(r in literalExpected.indices)for(c in literalExpected[r].indices)if(literalRaw.grid.values()[r][c]==literalExpected[r][c])check(literalRefined.grid.values()[r][c]==literalExpected[r][c])
        tableSmokeStage="frozen-fixtures"
        for(f in org.synapseworks.pageharbor.table.FrozenTables.cases) {
            val result=org.synapseworks.pageharbor.table.TableParser.parse(f.text,f.layout()) ?: error("Fixture unavailable")
            check(result.grid.values()==f.expected && result.concerns.isNotEmpty()==f.uncertain)
        }
        check(org.synapseworks.pageharbor.table.FrozenTables.csvInput.map{row->row.map(org.synapseworks.pageharbor.table.TableCsv::outgoing)}==org.synapseworks.pageharbor.table.FrozenTables.csvExpected)
        File(app.cacheDir,"m22-optimized-contract.csv").writeBytes(checkNotNull(org.synapseworks.pageharbor.table.TableCsv.encode(org.synapseworks.pageharbor.table.TableGrid.from(org.synapseworks.pageharbor.table.FrozenTables.csvInput))))
        File(app.cacheDir,"m22-optimized-contract-expected.json").writeText(org.json.JSONArray(org.synapseworks.pageharbor.table.FrozenTables.csvExpected).toString())
        org.synapseworks.pageharbor.table.M22DocumentsProvider.reset(app)
        val uri=org.synapseworks.pageharbor.table.M22DocumentsProvider.create(app)
        val file=File(app.cacheDir,"m22-optimized-input.png").apply{writeBytes(bytes)}
        tableSmokeStage="activity"
        val activity=startActivitySync(android.content.Intent(app,org.synapseworks.pageharbor.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as org.synapseworks.pageharbor.MainActivity
        val model=activity.table
        val canceled=java.util.concurrent.atomic.AtomicBoolean();val saves=java.util.concurrent.atomic.AtomicInteger();val images=java.util.concurrent.atomic.AtomicInteger()
        val monitor=object:ActivityMonitor() {
            override fun onStartActivity(intent:android.content.Intent):ActivityResult? {
                if(intent.action==android.content.Intent.ACTION_GET_CONTENT){check(intent.type=="image/*");images.incrementAndGet();return ActivityResult(Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.fromFile(file)))}
                if(intent.action==android.content.Intent.ACTION_CREATE_DOCUMENT){check(intent.type=="text/csv" && intent.getStringExtra(android.content.Intent.EXTRA_TITLE)=="table.csv");saves.incrementAndGet();return if(canceled.get())ActivityResult(Activity.RESULT_CANCELED,null)else ActivityResult(Activity.RESULT_OK,android.content.Intent().setData(uri))}
                return null
            }
        }
        fun lookup(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
            val root=uiAutomation.rootInActiveWindow ?: return null
            if(root.packageName?.toString()!=targetContext.packageName)return null
            val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();queue.add(root);var count=0
            while(queue.isNotEmpty() && count++<1024){val node=queue.removeFirst();if(predicate(node))return node;for(index in 0 until node.childCount)node.getChild(index)?.let(queue::addLast)}
            return null
        }
        fun until(condition:()->Boolean){val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)};error("Table workflow timeout")}
        fun activate(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {var current=node;repeat(6){val n=current ?: return false;if(n.isClickable && n.isEnabled)return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);current=n.parent};return false}
        fun scroll(){lookup{node->val r=android.graphics.Rect();node.getBoundsInScreen(r);node.isScrollable&&r.height()>r.width()}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}
        fun click(text:String){until{if(activate(lookup{it.text?.toString()==text||it.contentDescription?.toString()==text}))true else{scroll();false}}}
        fun confirm(){until{if(activate(lookup{it.isCheckable&&!it.isChecked}))true else{scroll();false}}}
        addMonitor(monitor)
        try {
            tableSmokeStage="more-entry";click(app.getString(R.string.navigation_more));tableSmokeStage="table-entry";click("Extract table")
            tableSmokeStage="registered-image";click("Choose table image");until{model.state?.phase==org.synapseworks.pageharbor.table.TablePhase.EDIT};check(images.get()==1);check(model.state!!.grid.values()==expected && model.state!!.grid.rows.flatten().none{it.corrected})
            tableSmokeStage="untouched-review";click("Review CSV grid");until{model.state?.phase==org.synapseworks.pageharbor.table.TablePhase.REVIEW};confirm();until{model.state?.confirmed==true};click("Save CSV");until{model.state?.problem=="CSV saved."}
            check(model.state!!.grid.values()==expected && model.state!!.grid.rows.flatten().none{it.corrected})
            File(app.cacheDir,"m22-automatic-optimized.csv").writeBytes(app.contentResolver.openInputStream(uri)!!.use{it.readBytes()})
            File(app.cacheDir,"m22-automatic-optimized-expected.json").writeText(org.json.JSONArray(expected).toString())
            click("Correct grid");until{model.state?.phase==org.synapseworks.pageharbor.table.TablePhase.EDIT}
            repeat(12){lookup{node->val r=android.graphics.Rect();node.getBoundsInScreen(r);node.isScrollable&&r.height()>r.width()}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);Thread.sleep(50)}
            val originalItem=model.state!!.grid.values()[1][1]
            tableSmokeStage="actual-cell-correction"
            until{val node=lookup{it.isEditable&&it.text?.toString()=="0012"};if(node!=null)node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"0099")})else{scroll();false}}
            until{model.state?.grid?.values()?.get(1)?.get(0)=="0099"}
            waitForIdleSync()
            tableSmokeStage="actual-ocr-to-manual"
            var itemNode:android.view.accessibility.AccessibilityNodeInfo?=null
            until{val node=lookup{it.isEditable&&it.text?.toString()?.contains(originalItem)==true};if(node!=null){itemNode=node;node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"MANUAL O0")})}else{scroll();false}}
            until{model.state?.grid?.values()?.get(1)?.get(1)=="MANUAL O0"}
            waitForIdleSync()
            tableSmokeStage="actual-ocr-restore"
            until{val node=itemNode?.takeIf{it.refresh()}?:lookup{it.isEditable&&it.text?.toString()?.contains("MANUAL O0")==true};node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expected[1][1])})==true}
            until{model.state?.grid?.values()?.get(1)?.get(1)==expected[1][1]}
            tableSmokeStage="actual-review";click("Review CSV grid");until{model.state?.phase==org.synapseworks.pageharbor.table.TablePhase.REVIEW}
            tableSmokeStage="actual-confirm";confirm();until{model.state?.confirmed==true}
            tableSmokeStage="registered-save";click("Save CSV");until{model.state?.problem=="CSV saved."}
            check(saves.get()==2 && org.synapseworks.pageharbor.table.M22DocumentsProvider.opens.get()==2)
            check(model.state!!.extraction!!.grid.values()[1][0]=="0012" && model.state!!.extraction!!.grid.values()[1][1]==originalItem)
            val outgoing=app.contentResolver.openInputStream(uri)!!.use{it.readBytes()};File(app.cacheDir,"m22-optimized.csv").writeBytes(outgoing)
            val corrected=expected.map{it.toMutableList()}.toMutableList();corrected[1][0]="0099";File(app.cacheDir,"m22-optimized-expected.json").writeText(org.json.JSONArray(corrected).toString())
            tableSmokeStage="registered-cancel";canceled.set(true);confirm();until{model.state?.confirmed==true};click("Save CSV");until{model.state?.phase==org.synapseworks.pageharbor.table.TablePhase.REVIEW&&model.state?.problem?.startsWith("Save canceled")==true}
            check(saves.get()==3 && org.synapseworks.pageharbor.table.M22DocumentsProvider.opens.get()==2)
            tableSmokeStage="background-clear";runOnMainSync{model.background(false);check(model.state!!.grid.rows.flatten().all{it.value.isEmpty()});model.setActive(true);model.close()}
            // This non-debuggable test package exposes only these fixed synthetic artifacts to adb.
            // No production source set includes this collector; no receipt/table contents are logged.
            tableSmokeStage="synthetic-artifact-collection"
            val evidence=File(checkNotNull(app.getExternalFilesDir(null)),"m22-evidence").apply{mkdirs()}
            for(name in listOf("m22-optimized.csv","m22-optimized-expected.json","m22-optimized-contract.csv","m22-optimized-contract-expected.json","m22-optical-evidence.json","m22-automatic-optimized.csv","m22-automatic-optimized-expected.json")) {
                File(app.cacheDir,name).copyTo(File(evidence,name),overwrite=true)
            }
        } finally {removeMonitor(monitor);file.delete();runOnMainSync{model.close();activity.finish()}}
    }

    private var receiptSmokeStage="fixture"
    private fun receiptFirstOffline() {
        val lines=targetContext.assets.open("optical-lines.txt").bufferedReader().use { it.readLines() }
        val bitmap=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888)
        val bytes=try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=54f;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL) }
            lines.forEachIndexed { index,text -> canvas.drawText(text,70f,100f+index*125f,paint) }
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray()
        } finally { bitmap.recycle() }
        receiptSmokeStage="recognition"
        val page=org.synapseworks.pageharbor.businesscard.CardRecognition.recognizePage({ByteArrayInputStream(bytes)}) ?: error("Receipt input unavailable")
        val result=org.synapseworks.pageharbor.receipt.ReceiptParser.parse(page.text,page.layout) ?: error("Receipt unavailable")
        val expected=mapOf(org.synapseworks.pageharbor.receipt.ReceiptField.MERCHANT to "HARBOR MARKET",org.synapseworks.pageharbor.receipt.ReceiptField.DATE to "2026-10-24",org.synapseworks.pageharbor.receipt.ReceiptField.CURRENCY to "EUR",
            org.synapseworks.pageharbor.receipt.ReceiptField.SUBTOTAL to "10.00",org.synapseworks.pageharbor.receipt.ReceiptField.TAX to "2.00",org.synapseworks.pageharbor.receipt.ReceiptField.TOTAL to "12.00",org.synapseworks.pageharbor.receipt.ReceiptField.TENDER to "20.00",org.synapseworks.pageharbor.receipt.ReceiptField.CHANGE to "8.00")
        receiptSmokeStage="optical-fields"
        check(result.draft().normalized(org.synapseworks.pageharbor.receipt.ReceiptRules())==expected)
        receiptSmokeStage="parser-fixtures"
        val cases=targetContext.assets.open("parser-cases.tsv").bufferedReader().use { it.readLines() };check(cases.size==15)
        for(line in cases) {
            val parts=line.split('\t');val text=org.json.JSONArray("["+parts[1]+"]").getString(0);val expectedJson=org.json.JSONObject(parts[2])
            val actual=org.synapseworks.pageharbor.receipt.ReceiptParser.parse(text)!!.draft().normalized(org.synapseworks.pageharbor.receipt.ReceiptRules())!!
            check(actual.size==expectedJson.length());for((field,value) in actual) check(value==expectedJson.getString(field.name))
        }
        val app=targetContext.applicationContext as android.app.Application
        receiptSmokeStage="activity"
        val activity=startActivitySync(android.content.Intent(app,org.synapseworks.pageharbor.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as org.synapseworks.pageharbor.MainActivity
        val file=File(app.cacheDir,"m21-optimized-synthetic.png")
        fun waitFor(condition:()->Boolean) { val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40)
            while(System.nanoTime()<end) { if(condition()) return;Thread.sleep(100) };error("Receipt workflow timeout") }
        // Traverse Compose virtual accessibility nodes, as UiAutomator does. The provider's
        // direct text search returned no More match despite the visible, clickable tree.
        fun findText(text:String): android.view.accessibility.AccessibilityNodeInfo? {
            val root=uiAutomation.rootInActiveWindow ?: return null
            if(root.packageName?.toString()!=targetContext.packageName) return null
            val pending=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();pending.add(root)
            var visited=0
            while(pending.isNotEmpty() && visited++<512) {
                val node=pending.removeFirst()
                if(node.text?.toString()?.contains(text)==true || node.contentDescription?.toString()?.contains(text)==true) return node
                for(index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
            }
            return null
        }
        fun clickText(text:String) { waitFor {
            var current=findText(text);var clicked=false
            repeat(5) { if(!clicked && current!=null) { if(current!!.isClickable) clicked=current!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) else current=current!!.parent } }
            clicked
        } }
        try {
            file.writeBytes(bytes)
            receiptSmokeStage="more-entry"
            clickText(app.getString(R.string.navigation_more));receiptSmokeStage="receipt-entry";clickText("Extract receipt")
            lateinit var model:org.synapseworks.pageharbor.receipt.ReceiptViewModel
            receiptSmokeStage="workflow-image-picker"
            runOnMainSync { model=activity.receipt;check(model.state!=null) }
            val picker=object:ActivityMonitor() {
                override fun onStartActivity(intent:android.content.Intent):ActivityResult? {
                    if(intent.action!=android.content.Intent.ACTION_GET_CONTENT) return null
                    check(intent.type=="image/*" && intent.hasCategory(android.content.Intent.CATEGORY_OPENABLE))
                    return ActivityResult(Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.fromFile(file)))
                }
            }
            addMonitor(picker)
            try { clickText("Choose receipt image");waitFor { model.state?.phase==org.synapseworks.pageharbor.receipt.ReceiptPhase.EDIT } }
            finally { removeMonitor(picker) }
            receiptSmokeStage="workflow-fields-correction"
            runOnMainSync { val state=model.state!!;check(state.draft.normalized(state.rules)==expected)
                model.edit(state.owner,state.epoch,org.synapseworks.pageharbor.receipt.ReceiptField.TOTAL,"13.20");model.review(state.owner,state.epoch)
                check(model.state!!.reviewed!![org.synapseworks.pageharbor.receipt.ReceiptField.TOTAL]=="13.20")
                check(model.state!!.extraction!!.fields.getValue(org.synapseworks.pageharbor.receipt.ReceiptField.TOTAL).candidates.single().raw=="12.00") }
            receiptSmokeStage="review-presentation"
            waitFor { findText("Reviewed receipt")!=null }
            receiptSmokeStage="background-clear"
            runOnMainSync { model.background(false);check(model.state!!.draft.values.isEmpty());model.setActive(true);model.close() }
        } finally { file.delete();runOnMainSync { activity.receipt.close();activity.finish() } }
    }

    private fun compactEmailAutomatic() {
        val bytes = targetContext.assets.open("compact-original.png").use { it.readBytes() }
        val expected = targetContext.assets.open("compact-email.txt").bufferedReader().use { it.readText() }
        val manifest = org.json.JSONObject(targetContext.assets.open("compact-fixture.json").bufferedReader().use { it.readText() })
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == manifest.getString("pngSha256"))
        var raw: org.synapseworks.pageharbor.ocr.OcrPageResult? = null
        val engine = object : org.synapseworks.pageharbor.ocr.OcrEngine {
            override fun recognize(pages: List<OcrPage>): org.synapseworks.pageharbor.ocr.OcrResult =
                MlKitOcrEngine().recognize(pages).also { raw = it.pages.single() }
        }
        val result = org.synapseworks.pageharbor.businesscard.CardRecognition.recognize({ ByteArrayInputStream(bytes) }, engine = engine)
            ?: error("Card recognition unavailable")
        check(result.draft.emails == listOf(expected))
        check(result.source == raw!!.text)
        check(raw!!.layout!!.lines.any { line -> line.elements.joinToString("") { it.text }.contains(expected) })
    }

    private var cardSmokeStage = "fixture"

    private fun businessCard() {
        val bytes = targetContext.assets.open("compact-original.png").use { it.readBytes() }
        val expectedEmail = targetContext.assets.open("compact-email.txt").bufferedReader().use { it.readText() }
        cardSmokeStage = "recognition"
        val result = org.synapseworks.pageharbor.businesscard.CardRecognition.recognize({ ByteArrayInputStream(bytes) }) ?: error("Card recognition unavailable")
        cardSmokeStage = "name-company"
        check(result.draft.name == "Jane Example" && result.draft.organization == "Harbor Studio")
        cardSmokeStage = "phone-exact"
        check(result.draft.phones == listOf("+1 202 555 0100"))
        cardSmokeStage = "automatic-email-exact"
        check(result.draft.emails == listOf(expectedEmail))
        // Separate manual correction regression: deliberately wrong recognized/suggested value.
        val incorrect = result.draft.copy(emails = listOf("wrong@example.invalid"))
        val draft = incorrect.edit(org.synapseworks.pageharbor.businesscard.ContactField.EMAIL, 0, expectedEmail)
            .copy(name = "Zoë Corrected", addresses = listOf("12 Rue de l'Étude\nSuite 4"))
        cardSmokeStage = "intent-construction"
        val intent = org.synapseworks.pageharbor.businesscard.ContactEditorIntent.create(draft) ?: error("Card editor unavailable")
        cardSmokeStage = "intent-action"
        check(intent.action == android.content.Intent.ACTION_INSERT && intent.type == android.provider.ContactsContract.Contacts.CONTENT_TYPE)
        cardSmokeStage = "intent-extras"
        check(intent.extras!!.keySet() == setOf(android.provider.ContactsContract.Intents.Insert.DATA, android.provider.ContactsContract.Intents.Insert.NAME, android.provider.ContactsContract.Intents.Insert.FULL_MODE))
        @Suppress("DEPRECATION") val rows = intent.getParcelableArrayListExtra<android.content.ContentValues>(android.provider.ContactsContract.Intents.Insert.DATA)!!
        cardSmokeStage = "intent-name"
        check(intent.getStringExtra(android.provider.ContactsContract.Intents.Insert.NAME) == "Zoë Corrected")
        cardSmokeStage = "intent-full-mode"
        check(intent.getBooleanExtra(android.provider.ContactsContract.Intents.Insert.FULL_MODE, false))
        check(rows.any { it.getAsString(android.provider.ContactsContract.Data.MIMETYPE) == android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && it.getAsString(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS) == "jane@example.invalid" })
        cardSmokeStage = "canonical-seven-line-fixture"
        val canonicalBitmap = Bitmap.createBitmap(1600, 1100, Bitmap.Config.ARGB_8888)
        val canonicalBytes = try {
            val canvas = Canvas(canonicalBitmap); canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) }
            listOf("Name: Jane Example", "Company: Harbor Studio", "Title: Design Lead", "Phone: +1 202 555 0100", "Email: jane@example.invalid", "Address: 12 Sample Road", "Dublin D02 AB12").forEachIndexed { index, text -> canvas.drawText(text, 70f, 100f + index * 125f, paint) }
            ByteArrayOutputStream().also { check(canonicalBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { canonicalBitmap.recycle() }
        cardSmokeStage = "canonical-exact-ocr-fields"
        val canonical = org.synapseworks.pageharbor.businesscard.CardRecognition.recognize({ ByteArrayInputStream(canonicalBytes) }) ?: error("Card recognition unavailable")
        check(canonical.draft.name == "Jane Example" && canonical.draft.organization == "Harbor Studio" && canonical.draft.title == "Design Lead")
        check(canonical.draft.phones == listOf("+1 202 555 0100") && canonical.draft.emails == listOf("jane@example.invalid"))
        check(canonical.draft.addresses == listOf("12 Sample Road\nDublin D02 AB12"))
        cardSmokeStage = "stale-controller"
        val controller = org.synapseworks.pageharbor.businesscard.CardController(); controller.open()
        val old = controller.state!!; val ticket = controller.capture(old.owner, old.epoch)!!
        controller.reset(); check(!controller.captured(ticket)); check(controller.handoff(old.owner, old.epoch) == null)
    }

    private fun qrCode() {
        check(targetContext.checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_DENIED)
        for(name in listOf("web","web-90","web-180","web-270","web-inverted","unicode","controls","unsupported","userinfo","dense","dense-90","dense-180","dense-270")){
            val expected=targetContext.assets.open("$name.txt").bufferedReader(Charsets.UTF_8).use{it.readText()}
            val decoded=org.synapseworks.pageharbor.qr.QrImageReader.read({targetContext.assets.open("$name.png")})
            check(decoded is org.synapseworks.pageharbor.qr.QrDecode.Found && decoded.payload.text==expected)
            if(name in listOf("unsupported","userinfo","controls"))check((decoded as org.synapseworks.pageharbor.qr.QrDecode.Found).payload.destination==null)
        }
        for(name in listOf("structured-0","structured-1"))check(org.synapseworks.pageharbor.qr.QrImageReader.read({targetContext.assets.open("$name.png")})==org.synapseworks.pageharbor.qr.QrDecode.Invalid)
        val controller=org.synapseworks.pageharbor.qr.QrController();check(controller.open())
        val old=controller.frame()!!;controller.rescan();controller.completeFrame(old,org.synapseworks.pageharbor.qr.QrDecode.Found(org.synapseworks.pageharbor.qr.QrPayload.accept("Synthetic optimized QR")!!));check(controller.state!!.result==null)
        val analysis=org.synapseworks.pageharbor.qr.QrCameraConfiguration.analysis(android.view.Surface.ROTATION_0)
        check(analysis.backpressureStrategy==androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);analysis.clearAnalyzer()
    }

    private fun collage()=kotlinx.coroutines.runBlocking {
        val root=File(targetContext.cacheDir,"shared-pdfs/m16-optimized").apply{mkdirs()}
        val file=File(root,"source.jpg");val image=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
        try{image.eraseColor(Color.RED);file.outputStream().use{check(image.compress(Bitmap.CompressFormat.JPEG,100,it))}}finally{image.recycle()}
        val processor=org.synapseworks.pageharbor.document.collage.CollageProcessor(targetContext);val work=processor.begin()
        try{
            val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),
                org.synapseworks.pageharbor.document.session.DocumentResource(android.net.Uri.fromFile(file).toString(),org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL),org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SELECTED_IMAGE)
            val before=org.synapseworks.pageharbor.document.session.DocumentSession(listOf(page))
            val draft=org.synapseworks.pageharbor.document.collage.CollageDraft(List(7){org.synapseworks.pageharbor.document.collage.CollageTile("tile-$it",1)},
                org.synapseworks.pageharbor.document.collage.CollageSettings(6,org.synapseworks.pageharbor.document.cardcopy.CardCopyPageSize.LETTER))
            check(processor.preview(work,draft,before).size==2)
            val output=processor.prepare(work,draft,before);val after=requireNotNull(org.synapseworks.pageharbor.document.collage.collageSession(before,output))
            check(after.pages.size==3&&after.pages[0]===page&&after.pages[1].annotations.isEmpty)
            check(output.pages[0].metadata.width==1530&&output.pages[0].metadata.height==1980)
            val plan=org.synapseworks.pageharbor.document.normalPdfExportPlan(after.copy(pages=listOf(after.pages.last())))as org.synapseworks.pageharbor.document.NormalPdfExportPlan.RecomposeFromPages
            val pdf=org.synapseworks.pageharbor.document.recomposeNormalPdf(targetContext,plan.pages)as org.synapseworks.pageharbor.document.NormalPdfRecompositionResult.Ready
            try{com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf.file).use{check(it.getPage(0).mediaBox.width==612f&&it.getPage(0).mediaBox.height==792f)}}finally{org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition(pdf.file)}
        }finally{processor.finish(work);root.deleteRecursively()}
    }

    private fun organization()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(targetContext,LibraryDatabase::class.java).build()
        try {
            val dao=db.libraryDao()
            val d=org.synapseworks.pageharbor.library.LibraryDocumentEntity(documentId="optimized-org",title="Synthetic metadata",createdAtMillis=1,modifiedAtMillis=1,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED")
            val p=org.synapseworks.pageharbor.library.LibraryPageEntity(pageId="optimized-org-page",documentId=d.documentId,position=0,relativePath="synthetic",contentType="image/jpeg",sourceCategory="SCAN",width=1,height=1,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",ocrText=null,ocrError=null)
            dao.replaceDocument(d,listOf(p),"")
            val repo=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(dao)
            val created=repo.create("Synthetic metadata",d.documentId) as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved
            check(repo.favorite(d.documentId,true) is org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved)
            val result=repo.page(null,"synthetic",org.synapseworks.pageharbor.library.LibrarySortOrder.MODIFIED_DESC,
                org.synapseworks.pageharbor.library.organization.LibraryOrganizationFilter(setOf(created.tagId!!),favoritesOnly=true),0).first()
            check(result.items.single().isFavorite)
            val record=BackupDocumentRecord("synthetic",null,"Synthetic",1,1,1,null,0,0,favorite=true,tagIds=listOf(created.tagId))
            val bytes=ByteArrayOutputStream();BackupJsonCodec.writeDocuments(sequenceOf(record),bytes)
            var decoded:BackupDocumentRecord?=null;BackupJsonCodec.readDocuments(ByteArrayInputStream(bytes.toByteArray())){decoded=it}
            check(decoded==record)
        } finally {db.close()}
    }

    private fun recycle()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(targetContext,LibraryDatabase::class.java).build()
        try {
            val dao=db.libraryDao()
            val d=org.synapseworks.pageharbor.library.LibraryDocumentEntity(documentId="synthetic-recycle",title="Synthetic",createdAtMillis=1,modifiedAtMillis=1,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED")
            val p=org.synapseworks.pageharbor.library.LibraryPageEntity(pageId="synthetic-recycle-page",documentId=d.documentId,position=0,relativePath="synthetic",contentType="image/jpeg",sourceCategory="SCAN",width=1,height=1,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",ocrText=null,ocrError=null)
            dao.replaceDocument(d,listOf(p),"")
            dao.rememberArchiveOrigin(org.synapseworks.pageharbor.library.recycle.DocumentArchiveOrigin(d.documentId,"synthetic-archive-id"))
            val t=dao.trashSavedDocument(d.documentId,2)!!;check(dao.document(d.documentId)==null&&dao.isDeletedArchiveIdentity("synthetic-archive-id"))
            check(dao.pagesAnyState(d.documentId)==listOf(p))
            check(dao.restoreTrashedDocument(d.documentId,t.trashRevision,3)!=null&&!dao.isDeletedArchiveIdentity(d.documentId))
            val t2=dao.trashSavedDocument(d.documentId,4)!!;check(dao.purgeTrashedDocument(d.documentId,t2.trashRevision,5)!=null)
            check(dao.documentAnyState(d.documentId)==null&&dao.isDeletedArchiveIdentity("synthetic-archive-id"))
        }finally{db.close()}
    }

    private fun roomReflection() {
        val database = Room.inMemoryDatabaseBuilder(targetContext, LibraryDatabase::class.java).build()
        try {
            database.openHelper.readableDatabase.query("PRAGMA user_version").use {
                check(it.moveToFirst() && it.getInt(0) == 10)
            }
            database.libraryDao()
        } finally {
            database.close()
        }
    }

    private fun toolSerialization() {
        val original = ToolInvocationContext(
            source = ToolInvocationSource.DOCUMENT_DETAIL,
            operation = DocumentToolOperation.EXTRACT_TEXT,
            documentIds = listOf("synthetic-document"),
            pageIds = listOf("synthetic-page"),
        ).requireReady()
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(original) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as ToolInvocationContext
        }
        check(original == restored && restored.hasValidCardinality)
    }

    private fun pdfResources() {
        check(targetContext.getString(R.string.app_name).contains("RME"))
        PDFBoxResourceLoader.init(targetContext)
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(40f, 700f)
                stream.showText("Synthetic R8 resource check")
                stream.endText()
            }
            document.save(bytes)
        }
        PDDocument.load(bytes.toByteArray()).use { document ->
            check(document.numberOfPages == 1)
            check(PDFTextStripper().getText(document).trim() == "Synthetic R8 resource check")
        }
    }

    private fun bundledOcr() {
        val bitmap = Bitmap.createBitmap(1000, 300, Bitmap.Config.ARGB_8888)
        val bytes = try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("MILESTONE", 40f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 90f
                })
            }
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
        val result = MlKitOcrEngine().recognize(listOf(OcrPage { ByteArrayInputStream(bytes) }))
        check(result.pages.single().error == null)
        check(result.pages.single().text.contains("MILESTONE", ignoreCase = true))
    }

    private fun searchablePdf() = runBlocking {
        val marker = "Română ă ș ț 漢字"
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
        val output = File.createTempFile("m04-smoke-", ".pdf", targetContext.cacheDir)
        try {
            val generator = PdfBoxSearchablePdfGenerator(targetContext)
            val page = SearchablePdfPage(
                openJpegStream = { ByteArrayInputStream(jpeg) },
                ocrResult = OcrPageResult(
                    pageIndex = 0, text = marker,
                    layout = OcrPageLayout(
                        imageWidthPx = 320, imageHeightPx = 240,
                        lines = listOf(OcrTextLine(marker, OcrTextBounds(24f, 40f, 296f, 72f))),
                    ),
                ),
            )
            check(generator.generate(SearchablePdfRequest(listOf(page), output)) ==
                SearchablePdfGenerationResult.Success(pageCount = 1, textLayerPageCount = 1))
            PDDocument.load(output).use {
                check(PDFTextStripper().getText(it).trim() == marker)
            }
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                check(it.pageCount == 1)
            }
            val invalid = SearchablePdfPage({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
            check(generator.generate(SearchablePdfRequest(listOf(invalid), output)) is
                SearchablePdfGenerationResult.Failure)
            check(!output.exists())
        } finally {
            check(!output.exists() || output.delete())
        }
    }

    private fun selectedPageBatch() {
        val source=org.synapseworks.pageharbor.document.session.DocumentSession((1L..3L).map {
            org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(it),
                org.synapseworks.pageharbor.document.session.DocumentResource("synthetic",org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL),
                org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN)
        })
        val recipe=org.synapseworks.pageharbor.document.batch.BatchPageEdit(brightness=20,contrast=25,clockwiseQuarterTurns=1)
        val result=org.synapseworks.pageharbor.document.batch.prepareBatchEdit(source,setOf(1,3),recipe)
        check(result.changedPages==2)
        check(result.session.pages[1]===source.pages[1])
        check(result.session.pages[0].adjustments.apply(0xff404040.toInt())==0xff636363.toInt())
        check(result.session.pages[2].rotation.degrees==90)
        check(targetContext.getString(R.string.batch_title).isNotBlank())
        lateinit var owner: org.synapseworks.pageharbor.PageHarborSessionViewModel
        runOnMainSync {
            owner=org.synapseworks.pageharbor.PageHarborSessionViewModel()
            owner.installDocumentSessionForTest(source)
            owner.batchEditor.open()
            check(owner.batchEditor.selectRange("1,3"))
            owner.batchEditor.setEdit(recipe)
            owner.batchEditor.apply()
        }
        try {
            runBlocking { kotlinx.coroutines.withTimeout(30_000) {
                while(owner.batchEditor.state?.busy==true) kotlinx.coroutines.delay(10)
            } }
            runOnMainSync {
                check(owner.batchEditor.state?.outcome==org.synapseworks.pageharbor.document.batch.BatchEditOutcome.APPLIED)
                check(owner.documentSession==result.session)
                owner.batchEditor.undo()
                check(owner.documentSession==source)
            }
        } finally { runOnMainSync { owner.batchEditor.close() } }
    }

    private fun shadowGlare() {
        val w = 192; val h = 128
        val pixels = IntArray(w*h) { i ->
            val x=i%w; val y=i/w; val ink=y%24 in 6..9 && x%31 in 3..25
            val gain=0.45f+0.55f*x/(w-1)
            Color.rgb(((if(ink)30 else 232)*gain).toInt(), ((if(ink)34 else 228)*gain).toInt(), ((if(ink)40 else 219)*gain).toInt())
        }
        val original=pixels.copyOf()
        val image=org.synapseworks.pageharbor.image.ArgbImage(w,h,pixels)
        val filter=org.synapseworks.pageharbor.image.DocumentFilter.valueOf("SHADOW_GLARE_STRONG_V1")
        val params=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25)
        val spatial=org.synapseworks.pageharbor.image.DocumentImageFilterEngine.apply(image,filter)
        check(!spatial.pixels.contentEquals(pixels)) // Exercise real correction, not only a no-reference fallback.
        val adjusted=org.synapseworks.pageharbor.image.DocumentImageFilterEngine.apply(image,filter,params)
        check(adjusted.pixels.contentEquals(spatial.pixels.map(params::apply).toIntArray()))
        check(pixels.contentEquals(original))
        check(targetContext.getString(R.string.shadow_title).isNotBlank())
        val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        val png=ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,png)) } finally { bitmap.recycle() }
        val source=png.toByteArray(); val output=ByteArrayOutputStream()
        check(org.synapseworks.pageharbor.document.writeFilteredJpegToDestination(
            openSource={ ByteArrayInputStream(source) },destination=output,filter=filter,adjustments=params
        ) == org.synapseworks.pageharbor.document.PageExportResult.Success)
        val encoded=output.toByteArray()
        val decoded=android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.size)
        try {
            check(decoded.width==w && decoded.height==h)
            for ((x,y) in listOf(16 to 8,160 to 8,16 to 16,160 to 16)) {
                val expected=adjusted.pixels[y*w+x];val actual=decoded.getPixel(x,y)
                for(shift in listOf(0,8,16)) check(kotlin.math.abs((expected ushr shift and 255)-(actual ushr shift and 255))<=3)
            }
        } finally { decoded.recycle() }
    }

    private fun documentHistory()=kotlinx.coroutines.runBlocking {
        val root=java.io.File(targetContext.cacheDir,"shared-pdfs/m13-smoke-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val file=java.io.File(root,"source.bin").apply{writeBytes(ByteArray(128){it.toByte()})}
        val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),
            org.synapseworks.pageharbor.document.session.DocumentResource(android.net.Uri.fromFile(file).toString(),org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.RME_OWNED_TEMPORARY,
                org.synapseworks.pageharbor.document.session.OwnedTemporaryFile(file.path,root.path)),org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN)
        val vm=org.synapseworks.pageharbor.PageHarborSessionViewModel()
        try{
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){vm.installDocumentSessionForTest(org.synapseworks.pageharbor.document.session.DocumentSession(listOf(page)));vm.configureHistory(org.synapseworks.pageharbor.document.history.AndroidHistorySourceAccess(targetContext))}
            vm.awaitHistoryIdle();val before=vm.documentSession
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){check(vm.rotatePageClockwise(1))};vm.awaitHistoryIdle();val after=vm.documentSession
            repeat(3){kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){check(vm.undoDocument())};vm.awaitHistoryIdle();check(vm.documentSession===before)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){check(vm.redoDocument())};vm.awaitHistoryIdle();check(vm.documentSession===after)}
            check(vm.documentRevision==8L&&vm.historyState.undoCount==1&&file.exists())
        }finally{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){vm.clearScan()};vm.awaitHistoryIdle();check(!file.exists());vm.disposeHistoryForTest();root.deleteRecursively()}
    }

    private fun signatures()=kotlinx.coroutines.runBlocking {
        val root=java.io.File(targetContext.noBackupFilesDir,"synthetic-smoke-${java.util.UUID.randomUUID()}")
        val ink=org.synapseworks.pageharbor.document.signature.HandwrittenSignature(listOf(org.synapseworks.pageharbor.document.signature.SignatureStroke(listOf(org.synapseworks.pageharbor.document.signature.SignaturePoint(.5f,.5f)))),strokeWidthFraction=.04f)
        try {
            val store=org.synapseworks.pageharbor.document.signature.SignatureTemplateStore(root)
            val saved=store.save("Synthetic",ink).single()
            check(org.synapseworks.pageharbor.document.signature.SignatureTemplateStore(root).list().single().ink==ink)
            val mark=org.synapseworks.pageharbor.document.annotation.AnnotationMark.create("synthetic",org.synapseworks.pageharbor.document.annotation.AnnotationTool.SIGNATURE,org.synapseworks.pageharbor.document.annotation.AnnotationBounds(.1f,.1f,.9f,.9f),org.synapseworks.pageharbor.document.annotation.AnnotationStyle(rgb=0),signature=ink)
            val layer=org.synapseworks.pageharbor.document.annotation.AnnotationLayer.of(listOf(mark))
            store.delete(saved.id);check(store.list().isEmpty());check(org.synapseworks.pageharbor.document.annotation.AnnotationLayer.decode(layer.encode())==layer)
            val bitmap=android.graphics.Bitmap.createBitmap(400,200,android.graphics.Bitmap.Config.ARGB_8888)
            try{org.synapseworks.pageharbor.document.annotation.AnnotationRenderer.draw(bitmap,layer,org.synapseworks.pageharbor.document.session.DocumentPageRotation.DEGREES_0);check(android.graphics.Color.red(bitmap.getPixel(200,100))==0)}finally{bitmap.recycle()}
        }finally{kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){root.deleteRecursively()}}
    }

    private fun annotations() {
        val mark=org.synapseworks.pageharbor.document.annotation.AnnotationMark.create("optimized",org.synapseworks.pageharbor.document.annotation.AnnotationTool.TEXT,
            org.synapseworks.pageharbor.document.annotation.AnnotationBounds(.1f,.1f,.9f,.9f),text="Café\nRésumé")
        val layer=org.synapseworks.pageharbor.document.annotation.AnnotationLayer.of(listOf(mark))
        check(layer==org.synapseworks.pageharbor.document.annotation.AnnotationLayer.decode(layer.encode()))
        val bitmap=android.graphics.Bitmap.createBitmap(800,600,android.graphics.Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(android.graphics.Color.WHITE);org.synapseworks.pageharbor.document.annotation.AnnotationRenderer.draw(bitmap,layer,org.synapseworks.pageharbor.document.session.DocumentPageRotation.DEGREES_0)
            val pixels=IntArray(800*600);bitmap.getPixels(pixels,0,800,0,0,800,600);check(pixels.count{it!=android.graphics.Color.WHITE}>100)
        } finally { bitmap.recycle() }
    }

    private fun pageCurvature()=kotlinx.coroutines.runBlocking {
        val root=java.io.File(targetContext.cacheDir,"shared-pdfs/m12-smoke-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val source=java.io.File(root,"curved.jpg");val width=480;val height=640;val pixels=IntArray(width*height){android.graphics.Color.WHITE}
        for(line in 0 until 17)for(x in 15 until 460){
            val offset=(.055*kotlin.math.cos(kotlin.math.PI*x/(width-1))*(height-1)).toInt()
            val baseline=85+line*27+offset;val phase=x%11
            for(d in -8..0)if(phase in 0..1||d in -8..-7||d in -1..0){val y=baseline+d;if(y in 0 until height)pixels[y*width+x]=android.graphics.Color.DKGRAY}
        }
        val bitmap=android.graphics.Bitmap.createBitmap(pixels,width,height,android.graphics.Bitmap.Config.ARGB_8888)
        try{source.outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it))}}finally{bitmap.recycle()}
        val processor=org.synapseworks.pageharbor.document.curvature.CurvatureProcessor(targetContext);val work=processor.begin()
        try{
            val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),
                org.synapseworks.pageharbor.document.session.DocumentResource(android.net.Uri.fromFile(source).toString(),org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL),
                org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN)
            val estimate=processor.analyze(work,page);check(estimate.estimate.status==org.synapseworks.pageharbor.document.curvature.CurvatureStatus.PROPOSED)
            val replacement=processor.prepare(work,org.synapseworks.pageharbor.document.curvature.CurvatureReview(page,estimate))
            val session=requireNotNull(org.synapseworks.pageharbor.document.curvature.curvatureSession(org.synapseworks.pageharbor.document.session.DocumentSession(listOf(page)),replacement))
            check(session.pages.single().id!=page.id&&session.pages.single().imageMetadata.height!!>height)
            val plan=org.synapseworks.pageharbor.document.normalPdfExportPlan(session)as org.synapseworks.pageharbor.document.NormalPdfExportPlan.RecomposeFromPages
            val pdf=org.synapseworks.pageharbor.document.recomposeNormalPdf(targetContext,plan.pages)as org.synapseworks.pageharbor.document.NormalPdfRecompositionResult.Ready
            try{check(pdf.file.length()>0)}finally{org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition(pdf.file)}
        }finally{processor.finish(work);root.deleteRecursively()}
    }

    private fun bookSplit()=kotlinx.coroutines.runBlocking {
        val root=java.io.File(targetContext.cacheDir,"shared-pdfs/m11-smoke-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val source=java.io.File(root,"spread.jpg");val bitmap=android.graphics.Bitmap.createBitmap(480,320,android.graphics.Bitmap.Config.ARGB_8888)
        try{val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.DKGRAY);val paint=android.graphics.Paint()
            paint.color=android.graphics.Color.WHITE;canvas.drawRect(34f,22f,446f,298f,paint)
            paint.color=android.graphics.Color.BLACK;canvas.drawRect(175f,22f,181f,298f,paint)
            for(y in 55..270 step 17){canvas.drawRect(55f,y.toFloat(),150f,y+2f,paint);canvas.drawRect(205f,y.toFloat(),425f,y+2f,paint)}
            source.outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it))}
        }finally{bitmap.recycle()}
        val processor=org.synapseworks.pageharbor.document.booklayout.BookSplitProcessor(targetContext);val work=processor.begin()
        try{val page=org.synapseworks.pageharbor.document.session.DocumentPage(org.synapseworks.pageharbor.document.session.DocumentPageId(1),
            org.synapseworks.pageharbor.document.session.DocumentResource(android.net.Uri.fromFile(source).toString(),org.synapseworks.pageharbor.document.session.DocumentResourceOwnership.USER_OR_EXTERNAL),
            org.synapseworks.pageharbor.document.session.DocumentSourceCategory.SCAN)
            val analysis=processor.analyze(work,page);check(analysis.detection.status==org.synapseworks.pageharbor.document.booklayout.BookDetectionStatus.CONFIDENT)
            val halves=processor.prepare(work,listOf(org.synapseworks.pageharbor.document.booklayout.BookReviewedPage(page,analysis)),org.synapseworks.pageharbor.document.booklayout.BookReadingOrder.RIGHT_TO_LEFT)
            val session=requireNotNull(org.synapseworks.pageharbor.document.booklayout.bookSplitSession(org.synapseworks.pageharbor.document.session.DocumentSession(listOf(page)),halves));check(session.pages.size==2)
            val plan=org.synapseworks.pageharbor.document.normalPdfExportPlan(session)as org.synapseworks.pageharbor.document.NormalPdfExportPlan.RecomposeFromPages
            val pdf=org.synapseworks.pageharbor.document.recomposeNormalPdf(targetContext,plan.pages)as org.synapseworks.pageharbor.document.NormalPdfRecompositionResult.Ready
            try{check(pdf.file.length()>0)}finally{org.synapseworks.pageharbor.document.deleteNormalPdfRecomposition(pdf.file)}
        }finally{processor.finish(work);root.deleteRecursively()}
    }

    private fun continuousCapture() {
        val app=targetContext.applicationContext as android.app.Application
        check(androidx.core.content.ContextCompat.checkSelfPermission(app,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val foreground=startActivitySync(android.content.Intent(app,org.synapseworks.pageharbor.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val provider=androidx.camera.lifecycle.ProcessCameraProvider.getInstance(app).get(30,java.util.concurrent.TimeUnit.SECONDS)
        check(provider.hasCamera(androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA))
        val lifecycle=object:androidx.lifecycle.LifecycleOwner {
            val registry=androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle:androidx.lifecycle.Lifecycle get()=registry
        }
        var cleanupCapture:androidx.camera.core.ImageCapture?=null
        lateinit var capture:androidx.camera.core.ImageCapture
        var cleanupModel:org.synapseworks.pageharbor.document.capture.ContinuousCaptureViewModel?=null
        lateinit var model:org.synapseworks.pageharbor.document.capture.ContinuousCaptureViewModel
        var cleanupOwner:org.synapseworks.pageharbor.PageHarborSessionViewModel?=null
        lateinit var owner:org.synapseworks.pageharbor.PageHarborSessionViewModel
        try {
            runOnMainSync {
                lifecycle.registry.currentState=androidx.lifecycle.Lifecycle.State.RESUMED
                capture=org.synapseworks.pageharbor.document.capture.ContinuousCameraConfiguration.capture(android.view.Surface.ROTATION_0)
                cleanupCapture=capture
                provider.bindToLifecycle(lifecycle,androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,capture)
                owner=org.synapseworks.pageharbor.PageHarborSessionViewModel()
                cleanupOwner=owner
                model=org.synapseworks.pageharbor.document.capture.ContinuousCaptureViewModel(app)
                cleanupModel=model
                check(model.open(owner.beginContinuousRequest()!!,owner.documentRevision))
                model.capture { file,done -> capture.takePicture(androidx.camera.core.ImageCapture.OutputFileOptions.Builder(file).build(),
                    androidx.core.content.ContextCompat.getMainExecutor(app),object:androidx.camera.core.ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result:androidx.camera.core.ImageCapture.OutputFileResults){done(true)}
                        override fun onError(exception:androidx.camera.core.ImageCaptureException){done(false)}
                    }) }
            }
            val limit=android.os.SystemClock.elapsedRealtime()+30_000
            while(model.state?.busy==true&&android.os.SystemClock.elapsedRealtime()<limit)Thread.sleep(10)
            check(model.state?.pages?.size==1)
            val source=model.state!!.pages.single();check(source.metadata.width!!<=2000&&source.metadata.height!!<=2000)
            runOnMainSync{model.finish()}
            val input=requireNotNull(model.input())
            runOnMainSync{check(owner.completeContinuousRequest(model.request!!,model.state!!.revision,input));model.transferred()}
            val output=java.io.ByteArrayOutputStream()
            check(org.synapseworks.pageharbor.document.writeFilteredJpegToDestination({source.file.inputStream()},output,org.synapseworks.pageharbor.image.DocumentFilter.ORIGINAL)==org.synapseworks.pageharbor.document.PageExportResult.Success)
            check(output.size()>0)
            runOnMainSync{owner.clearScan()};check(!source.file.exists())
        } finally {
            runOnMainSync {
                cleanupModel?.cancel()
                cleanupOwner?.clearScan()
                cleanupCapture?.let{provider.unbind(it)}
                lifecycle.registry.currentState=androidx.lifecycle.Lifecycle.State.DESTROYED
                foreground.finish()
            }
        }
    }

    private fun backgroundWhitening() {
        val w = 192; val h = 128
        val pixels = IntArray(w*h) { i ->
            val x=i%w; val y=i/w; val ink=y%24 in 6..9 && x%31 in 3..25
            val gain=0.45f+0.55f*x/(w-1)
            Color.rgb(((if(ink)30 else 232)*gain).toInt(), ((if(ink)34 else 228)*gain).toInt(), ((if(ink)40 else 219)*gain).toInt())
        }
        val original=pixels.copyOf()
        val image=org.synapseworks.pageharbor.image.ArgbImage(w,h,pixels)
        val filter=org.synapseworks.pageharbor.image.DocumentFilter.valueOf("PAPER_WHITE_COLOUR_STRONG_V1")
        val params=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25)
        val spatial=org.synapseworks.pageharbor.image.DocumentImageFilterEngine.apply(image,filter)
        check(!spatial.pixels.contentEquals(pixels)) // Exercise real correction, not only a no-reference fallback.
        val adjusted=org.synapseworks.pageharbor.image.DocumentImageFilterEngine.apply(image,filter,params)
        check(adjusted.pixels.contentEquals(spatial.pixels.map(params::apply).toIntArray()))
        check(pixels.contentEquals(original))
        check(targetContext.getString(R.string.whitening_title).isNotBlank())
        val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        val png=ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,png)) } finally { bitmap.recycle() }
        val source=png.toByteArray(); val output=ByteArrayOutputStream()
        check(org.synapseworks.pageharbor.document.writeFilteredJpegToDestination(
            openSource={ ByteArrayInputStream(source) },destination=output,filter=filter,adjustments=params
        ) == org.synapseworks.pageharbor.document.PageExportResult.Success)
        val encoded=output.toByteArray()
        val decoded=android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.size)
        try {
            check(decoded.width==w && decoded.height==h)
            for ((x,y) in listOf(16 to 8,160 to 8,16 to 16,160 to 16)) {
                val expected=adjusted.pixels[y*w+x];val actual=decoded.getPixel(x,y)
                for(shift in listOf(0,8,16)) check(kotlin.math.abs((expected ushr shift and 255)-(actual ushr shift and 255))<=3)
            }
        } finally { decoded.recycle() }
    }

    private fun manualAdjustments() {
        val image = org.synapseworks.pageharbor.image.ArgbImage(1, 1, intArrayOf(0xff404040.toInt()))
        val params = org.synapseworks.pageharbor.image.ManualImageAdjustments(20, 25)
        val adjusted = org.synapseworks.pageharbor.image.DocumentImageFilterEngine.apply(image,
            org.synapseworks.pageharbor.image.DocumentFilter.ORIGINAL, params)
        check(adjusted.pixels.single() == 0xff636363.toInt())
        check(image.pixels.single() == 0xff404040.toInt())
        check(targetContext.getString(R.string.manual_adjust_title).isNotBlank())
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(Color.rgb(64, 96, 128))
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, source))
        } finally { bitmap.recycle() }
        val bytes = source.toByteArray()
        val output = ByteArrayOutputStream()
        check(org.synapseworks.pageharbor.document.writeFilteredJpegToDestination(
            openSource = { ByteArrayInputStream(bytes) }, destination = output,
            filter = org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE, adjustments = params,
        ) == org.synapseworks.pageharbor.document.PageExportResult.Success)
        val encoded = output.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        try { check(Color.red(decoded.getPixel(40, 40)) in 131..137) } finally { decoded.recycle() }
        val db = Room.inMemoryDatabaseBuilder(targetContext, LibraryDatabase::class.java).build()
        try {
            db.openHelper.readableDatabase.query("SELECT brightness, contrast FROM library_pages").close()
        } finally { db.close() }
    }

    private fun passwordEncryption() = runBlocking {
        check(!org.synapseworks.pageharbor.document.protectedpdf.isSupportedPdfPassword("😀".repeat(8).toCharArray()))
        check(org.synapseworks.pageharbor.document.protectedpdf.isSupportedPdfPassword("\uD840\uDD00".repeat(8).toCharArray()))
        targetContext.assets.open("licenses/Unicode-3.0.txt").use { check(it.read() >= 0) }
        val directory = File(targetContext.cacheDir, "m05-optimized-${UUID.randomUUID()}")
        check(directory.mkdir())
        val chars = CharArray(16) { (33 + SecureRandom().nextInt(90)).toChar() }
        try {
            val source = File(directory, "input.pdf")
            val output = File(directory, "encrypted.pdf")
            PDDocument().use { it.addPage(PDPage()); it.save(source) }
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val before = digest(source)
            check(PasswordProtectedPdf().create(source, output, chars) == PasswordPdfResult.Success(1))
            try {
                PDDocument.load(output).use { }
                error("An opening password must be required")
            } catch (_: InvalidPasswordException) { /* expected */ }
            try {
                PDDocument.load(output, "incorrect synthetic credential").use { }
                error("An incorrect opening password must be rejected")
            } catch (_: InvalidPasswordException) { /* expected */ }
            PDDocument.load(output, String(chars)).use {
                check(it.isEncrypted && it.numberOfPages == 1)
                check(it.encryption.version == 5 && it.encryption.revision == 6 && it.encryption.length == 256)
            }
            check(before.contentEquals(digest(source)))
            check(directory.listFiles().orEmpty().none { it.name.startsWith("password-work-") })
        } finally {
            chars.fill('\u0000')
            check(directory.deleteRecursively())
        }
    }

    private fun backupJson() {
        val original = BackupManifest(
            formatVersion = RME_BACKUP_FORMAT_VERSION,
            minimumReaderVersion = RME_BACKUP_READER_VERSION,
            requiredFeatures = emptyList(),
            backupId = "synthetic-backup",
            createdAtEpochMillis = 1L,
            producer = BackupProducer("org.synapseworks.pageharbor", "1.7.2", 21),
            summary = BackupSummary(0, 0, 0, 0, 0L),
            metadata = BackupMetadataPaths(
                RME_BACKUP_FOLDERS_PATH, RME_BACKUP_DOCUMENTS_PATH,
                RME_BACKUP_PAGES_PATH, RME_BACKUP_SOURCE_ASSETS_PATH,
            ),
            integrity = BackupIntegrity("SHA-256", RME_BACKUP_CHECKSUMS_PATH),
        )
        val bytes = ByteArrayOutputStream().also { BackupJsonCodec.writeManifest(original, it) }
            .toByteArray()
        check(original == BackupJsonCodec.readManifest(ByteArrayInputStream(bytes)))
    }
}
