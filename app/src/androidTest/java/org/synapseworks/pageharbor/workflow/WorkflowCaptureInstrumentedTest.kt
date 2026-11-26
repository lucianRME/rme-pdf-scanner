package org.synapseworks.pageharbor.workflow

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.*
import org.synapseworks.pageharbor.ocr.*

class WorkflowCaptureInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation();private val app=instrumentation.targetContext
    private lateinit var root:File;private lateinit var source:File;private lateinit var document:DocumentSession
    @Before fun before() {
        root=File(app.noBackupFilesDir,"workflow-capture-test-${UUID.randomUUID()}").apply{mkdirs()};source=File(root,"source.png")
        source.writeBytes(instrumentation.context.assets.open("m29/medium-left-512-reference.png").use{it.readBytes()})
        document=DocumentSession(listOf(DocumentPage(DocumentPageId(1),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SCAN,"image/png",DocumentImageMetadata(source.length(),512,384),filter=DocumentFilter.GRAYSCALE,adjustments=ManualImageAdjustments(-10,15))))
        PDFBoxResourceLoader.init(app)
    }
    @After fun after(){root.deleteRecursively()}
    private fun capture(preset:WorkflowPreset=WorkflowPreset.NORMAL,doc:DocumentSession=document,ocr:OcrResult?=null,password:CharArray=charArrayOf(),allowed:()->Boolean={true})=runBlocking{captureWorkflow(app,root,doc,preset,ocr,password,allowed)}
    @Test fun originalBytesAndCapturedPixelsMatchAfterSourceMutation() {
        val before=source.readBytes();val frozen=capture();val bytes=frozen.output.readBytes();source.writeBytes(byteArrayOf(0))
        assertEquals(workflowDigest(frozen.output.inputStream()),frozen.digest to frozen.bytes);assertArrayEquals(bytes,frozen.output.readBytes());assertTrue(frozen.preview.isFile)
        source.writeBytes(before);assertArrayEquals(before,source.readBytes())
    }
    @Test fun normalAndTargetPresetsPreserveRotatedGeometryAndRemainBounded() {
        for(rotation in DocumentPageRotation.entries) {
            val doc=document.copy(pages=document.pages.map{it.copy(rotation=rotation)})
            for(preset in listOf(WorkflowPreset.NORMAL,WorkflowPreset.SMALL,WorkflowPreset.LARGE)) {
                val r=capture(preset,doc)
                PdfRenderer(ParcelFileDescriptor.open(r.output,ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf -> pdf.openPage(0).use { page ->
                    if(rotation.degrees%180==0)assertTrue(page.width>page.height)else assertTrue(page.width<page.height)
                } }
                assertTrue(r.bytes<=if(preset==WorkflowPreset.SMALL)1024L*1024 else if(preset==WorkflowPreset.LARGE)5L*1024*1024 else WORKFLOW_MAX_PDF)
                r.directory.deleteRecursively()
            }
        }
    }
    @Test fun positionedOcrIsExtractableAndMissingOrRotatedOcrDeclines() {
        val text="Reference front 012345";val layout=OcrPageLayout(512,384,lines=listOf(OcrTextLine(text,OcrTextBounds(90f,30f,260f,48f))))
        val ocr=OcrResult(listOf(OcrPageResult(0,text,layout=layout)))
        val r=capture(WorkflowPreset.SEARCHABLE,ocr=ocr)
        PDDocument.load(r.output).use { assertTrue(PDFTextStripper().getText(it).contains(text)) };r.directory.deleteRecursively()
        assertThrows(IllegalArgumentException::class.java){capture(WorkflowPreset.SEARCHABLE)}
        val rotated=document.copy(pages=document.pages.map{it.copy(rotation=DocumentPageRotation.DEGREES_90)})
        assertThrows(IllegalArgumentException::class.java){capture(WorkflowPreset.SEARCHABLE,rotated,ocr)}
    }
    @Test fun encryptionUsesFreshSecretAndOnlyCipherOutputCanQueue() {
        val secret="Synthetic-M30-password".toCharArray();val r=capture(WorkflowPreset.ENCRYPTED,password=secret)
        assertTrue(secret.all{it=='\u0000'});assertNotEquals(r.output,r.preview)
        PDDocument.load(r.output,"Synthetic-M30-password").use { assertTrue(it.isEncrypted);assertEquals(1,it.numberOfPages) }
        assertThrows(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException::class.java){PDDocument.load(r.output).close()}
        r.directory.deleteRecursively();assertThrows(IllegalArgumentException::class.java){capture(WorkflowPreset.ENCRYPTED,password=charArrayOf())}
        assertTrue(root.listFiles()!!.none{it.isDirectory})
    }
    @Test fun changedSourcesCancellationMissingFilesAndResourceLimitsCleanAllCaptures() {
        var calls=0
        assertThrows(IllegalStateException::class.java){capture(allowed={++calls<3})}
        assertTrue(root.listFiles()!!.none{it.isDirectory})
        val original=source.readBytes();calls=0
        assertThrows(IllegalArgumentException::class.java){capture(allowed={if(++calls==4)source.appendBytes(byteArrayOf(1));true})}
        assertTrue(root.listFiles()!!.none{it.isDirectory});source.writeBytes(original)
        source.delete();assertThrows(Exception::class.java){capture()};assertTrue(root.listFiles()!!.none{it.isDirectory})
        val tooMany=document.copy(pages=(1L..21L).map{document.pages.single().copy(id=DocumentPageId(it))})
        assertThrows(IllegalArgumentException::class.java){capture(doc=tooMany)}
        assertThrows(IllegalArgumentException::class.java){capture(WorkflowPreset.OFFICE)}
    }
}
