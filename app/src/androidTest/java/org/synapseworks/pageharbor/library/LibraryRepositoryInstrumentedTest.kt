package org.synapseworks.pageharbor.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.synapseworks.pageharbor.document.session.toAndroidUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPage
import org.synapseworks.pageharbor.document.session.DocumentPageId
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentResource
import org.synapseworks.pageharbor.document.session.DocumentResourceOwnership
import org.synapseworks.pageharbor.document.session.DocumentSession
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.ocr.OcrPageLayout
import org.synapseworks.pageharbor.ocr.OcrPageResult
import org.synapseworks.pageharbor.ocr.OcrResult
import org.synapseworks.pageharbor.ocr.OcrTextBounds
import org.synapseworks.pageharbor.ocr.OcrTextLine
import org.synapseworks.pageharbor.ocr.persistence.BundledLatinOcrArtifactContract

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryInstrumentedTest {
    private val restartDatabaseName = "library-restart-test.db"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var fileStore: LibraryFileStore
    private val sourceDirectory = File(context.cacheDir, "library-test-sources")
    private val libraryDirectory = File(
        File(context.filesDir, LibraryFileStore.LIBRARY_DIRECTORY),
        "instrumentation-tests",
    )
    private var clock = 1_000L

    @Before
    fun setUp() {
        context.deleteDatabase(restartDatabaseName)
        sourceDirectory.mkdirs()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileStore = LibraryFileStore(context, libraryDirectory)
        repository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = fileStore,
            nowMillis = { ++clock },
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(restartDatabaseName)
        sourceDirectory.deleteRecursively()
        libraryDirectory.deleteRecursively()
    }

    @Test fun collagePreservesOriginalOcrCreatesFreshIdentityAndRejectsCorruptPhysicalPair()=runBlocking {
        val original=session(org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory))
        val saved=repository.saveSession(original,"Synthetic collage",ocrResult=OcrResult(listOf(OcrPageResult(0,"Source OCR")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val oldRow=database.libraryDao().pages(saved.id).single()
        val prepared=org.synapseworks.pageharbor.document.collage.CollageFixtures.prepare(context,opened)
        try {
            repository.saveSession(prepared.after,"Synthetic collage").successValue()
            val rows=database.libraryDao().pages(saved.id);assertEquals(3,rows.size)
            assertEquals(oldRow.activeOcrArtifactRevision,rows[0].activeOcrArtifactRevision);assertEquals(oldRow.ocrText,rows[0].ocrText)
            assertEquals(oldRow.visualRevision,rows[0].visualRevision)
            rows.drop(1).forEach{assertEquals(null,it.activeOcrArtifactRevision);assertEquals(null,it.ocrText);assertEquals("",it.annotations);assertEquals(0,it.brightness)}
            assertEquals(4,database.libraryDao().document(saved.id)!!.contentHashVersion)
            val reopened=repository.openDocument(saved.id).successValue().session
            assertEquals(prepared.after.pages.map{it.paperSize},reopened.pages.map{it.paperSize})
            assertTrue(fileStore.resolve(database.libraryDao().document(saved.id)!!.thumbnailRelativePath!!)!!.isFile)
            for((row,page)in rows.zip(reopened.pages))org.junit.Assert.assertArrayEquals(fileStore.resolve(row.relativePath)!!.readBytes(),context.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{it.readBytes()})
            database.openHelper.writableDatabase.execSQL("UPDATE library_pages SET paper_size = ? WHERE page_id = ?",arrayOf("7fc00000:44460000",rows[1].pageId))
            assertTrue(repository.openDocument(saved.id) is LibraryResult.Failure)
            database.openHelper.writableDatabase.execSQL("UPDATE library_pages SET paper_size = ? WHERE page_id = ?",arrayOf(org.synapseworks.pageharbor.document.session.DocumentPaperSize(612f,612f).encode(),rows[1].pageId))
            assertTrue(repository.openDocument(saved.id) is LibraryResult.Failure)
        } finally { prepared.close() }
    }

    @Test fun annotationLayerReopensEditsAndChangesIdentityWithoutChangingSourceOcr()=runBlocking {
        val original=session(org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory))
        val saved=repository.saveSession(original,"Synthetic annotations",ocrResult=OcrResult(listOf(OcrPageResult(0,"Source OCR")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=database.libraryDao().pages(saved.id).single()
        assertNotNull(before.activeOcrArtifactRevision)
        val docBefore=database.libraryDao().document(saved.id)!!
        val bytes=fileStore.resolve(before.relativePath)!!.readBytes()
        val layer=org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.layer()
        val changed=opened.copy(pages=listOf(opened.pages[0].copy(annotations=layer)))
        repository.saveSession(changed,"Synthetic annotations").successValue()
        val reopened=repository.openDocument(saved.id).successValue().session
        assertEquals(layer,reopened.pages.single().annotations)
        val after=database.libraryDao().pages(saved.id).single();val docAfter=database.libraryDao().document(saved.id)!!
        org.junit.Assert.assertArrayEquals(bytes,fileStore.resolve(after.relativePath)!!.readBytes())
        assertEquals(before.ocrText,after.ocrText);assertEquals(before.visualRevision,after.visualRevision);assertEquals(before.activeOcrArtifactRevision,after.activeOcrArtifactRevision)
        assertEquals(docBefore.contentRevision+1,docAfter.contentRevision);assertEquals(3,docAfter.contentHashVersion)
        org.junit.Assert.assertNotEquals(docBefore.contentSha256,docAfter.contentSha256)
        assertTrue(fileStore.resolve(docAfter.thumbnailRelativePath!!)!!.isFile)
        val edited=layer.put(layer.marks.last().withText("Edited Café"))
        repository.saveSession(reopened.copy(pages=listOf(reopened.pages[0].copy(annotations=edited))),"Synthetic annotations").successValue()
        assertEquals(edited,repository.openDocument(saved.id).successValue().session.pages[0].annotations)
    }

    @Test fun signatureLayerReopensEditsAndChangesIdentityWithoutChangingSourceOcr()=runBlocking {
        val original=session(org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory))
        val saved=repository.saveSession(original,"Synthetic annotations",ocrResult=OcrResult(listOf(OcrPageResult(0,"Source OCR")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=database.libraryDao().pages(saved.id).single()
        assertNotNull(before.activeOcrArtifactRevision)
        val docBefore=database.libraryDao().document(saved.id)!!
        val bytes=fileStore.resolve(before.relativePath)!!.readBytes()
        val layer=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer()
        val changed=opened.copy(pages=listOf(opened.pages[0].copy(annotations=layer)))
        repository.saveSession(changed,"Synthetic annotations").successValue()
        val reopened=repository.openDocument(saved.id).successValue().session
        assertEquals(layer,reopened.pages.single().annotations)
        val after=database.libraryDao().pages(saved.id).single();val docAfter=database.libraryDao().document(saved.id)!!
        org.junit.Assert.assertArrayEquals(bytes,fileStore.resolve(after.relativePath)!!.readBytes())
        assertEquals(before.ocrText,after.ocrText);assertEquals(before.visualRevision,after.visualRevision);assertEquals(before.activeOcrArtifactRevision,after.activeOcrArtifactRevision)
        assertEquals(docBefore.contentRevision+1,docAfter.contentRevision);assertEquals(3,docAfter.contentHashVersion)
        org.junit.Assert.assertNotEquals(docBefore.contentSha256,docAfter.contentSha256)
        assertTrue(fileStore.resolve(docAfter.thumbnailRelativePath!!)!!.isFile)
        val edited=layer.put(layer.marks.last().withText("Edited Café"))
        repository.saveSession(reopened.copy(pages=listOf(reopened.pages[0].copy(annotations=edited))),"Synthetic annotations").successValue()
        assertEquals(edited,repository.openDocument(saved.id).successValue().session.pages[0].annotations)
    }

    @Test fun corruptAnnotationLayerRejectsOpenMergeAndExtractWithoutPublishingPartialDocuments()=runBlocking {
        val original=session(org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory,88))
        val first=repository.saveSession(original,"First").successValue();val second=repository.saveSession(original,"Second").successValue()
        val row=database.libraryDao().pages(first.id).single()
        val bytes=fileStore.resolve(row.relativePath)!!.readBytes()
        database.openHelper.writableDatabase.execSQL("UPDATE library_pages SET annotations = ? WHERE page_id = ?",arrayOf("invalid",row.pageId))
        fun corrupted(result:LibraryResult<*>) { assertTrue(result is LibraryResult.Failure);assertEquals(LibraryError.CORRUPTED_RECORD,(result as LibraryResult.Failure).reason) }
        corrupted(repository.openDocument(first.id))
        corrupted(repository.mergeDocuments(listOf(first.id,second.id),"Merged"))
        corrupted(repository.extractPages(first.id,setOf(row.pageId),"Extracted",false))
        assertEquals(2,database.libraryDao().activeDocumentsPage(-1,100).size)
        org.junit.Assert.assertArrayEquals(bytes,fileStore.resolve(row.relativePath)!!.readBytes())
        assertEquals("invalid",database.libraryDao().pages(first.id).single().annotations)
    }

    @Test fun annotatedThumbnailUsesExifUprightCoordinatesBeforeSessionRotation()=runBlocking {
        val page=org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory,77)
        val source=File(Uri.parse(page.source.reference).path!!)
        android.media.ExifInterface(source.path).apply{setAttribute(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val mark=org.synapseworks.pageharbor.document.annotation.AnnotationMark.create("landmark",org.synapseworks.pageharbor.document.annotation.AnnotationTool.HIGHLIGHT,
            org.synapseworks.pageharbor.document.annotation.AnnotationBounds(.1f,.2f,.4f,.5f),org.synapseworks.pageharbor.document.annotation.AnnotationStyle(0x0000FF))
        val annotated=page.copy(rotation=DocumentPageRotation.DEGREES_90,annotations=org.synapseworks.pageharbor.document.annotation.AnnotationLayer.of(listOf(mark)),imageMetadata=page.imageMetadata.copy(sourceByteCount=source.length()))
        val saved=repository.saveSession(session(annotated),"Exif annotation").successValue();val doc=database.libraryDao().document(saved.id)!!
        val thumbnail=android.graphics.BitmapFactory.decodeFile(fileStore.resolve(doc.thumbnailRelativePath!!)!!.path)
        try { assertTrue(thumbnail.width>thumbnail.height)
            // Canonical(.2,.3) is displayed(.7,.2) under the session90 after EXIF90.
            val c=thumbnail.getPixel((thumbnail.width*.7f).toInt(),(thumbnail.height*.2f).toInt())
            assertTrue(android.graphics.Color.blue(c)>220);assertTrue(android.graphics.Color.red(c)<30)
        }finally{thumbnail.recycle()}
    }

    @Test fun signatureThumbnailUsesExifThenSessionRotationWithoutCoordinateTransplant()=runBlocking {
        val page=org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.source(sourceDirectory,78)
        val source=File(Uri.parse(page.source.reference).path!!)
        android.media.ExifInterface(source.path).apply{setAttribute(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
        val mark=org.synapseworks.pageharbor.document.signature.SignatureFixtures.mark()
        val annotated=page.copy(rotation=DocumentPageRotation.DEGREES_90,annotations=org.synapseworks.pageharbor.document.annotation.AnnotationLayer.of(listOf(mark)),imageMetadata=page.imageMetadata.copy(sourceByteCount=source.length()))
        val saved=repository.saveSession(session(annotated),"Synthetic EXIF ink").successValue();val doc=database.libraryDao().document(saved.id)!!
        val thumbnail=android.graphics.BitmapFactory.decodeFile(fileStore.resolve(doc.thumbnailRelativePath!!)!!.path)
        try {
            assertTrue(thumbnail.width>thumbnail.height)
            // Source dot(.61,.28) after EXIF upright and session90 appears at(.72,.61).
            val c=thumbnail.getPixel((thumbnail.width*.72f).toInt(),(thumbnail.height*.61f).toInt())
            assertTrue(android.graphics.Color.red(c)<70)
            assertEquals(mark.signature,repository.openDocument(saved.id).successValue().session.pages.single().annotations.marks.single().signature)
        }finally{thumbnail.recycle()}
    }

    @Test
    fun manualSettingsReopenWithoutBakingSourceOrInvalidatingOriginalOcr() = runBlocking {
        val original = session(page(1, "manual.jpg", 0xff406080.toInt()), page(2, "other.jpg", 0xff808080.toInt()))
        val bytes = context.contentResolver.openInputStream(original.pages[0].source.toAndroidUri())!!.use { it.readBytes() }
        val saved = repository.saveSession(original,"Synthetic manual",ocrResult=OcrResult(listOf(OcrPageResult(0,"synthetic OCR"),OcrPageResult(1,"other OCR")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val prior=database.libraryDao().pages(saved.id)[0]
        val revision=requireNotNull(database.libraryDao().document(saved.id)).contentRevision
        val changed=requireNotNull(opened.setAdjustments(opened.pages[0].id,org.synapseworks.pageharbor.image.ManualImageAdjustments(30,25)))
        repository.saveSession(changed,"Synthetic manual").successValue()
        val reopened=repository.openDocument(saved.id).successValue().session
        assertEquals(changed.pages[0].adjustments,reopened.pages[0].adjustments)
        assertEquals(org.synapseworks.pageharbor.image.ManualImageAdjustments(),reopened.pages[1].adjustments)
        assertEquals(opened.pages[0].persistentId,reopened.pages[0].persistentId)
        val after=database.libraryDao().pages(saved.id)[0]
        org.junit.Assert.assertArrayEquals(bytes,requireNotNull(fileStore.resolve(after.relativePath)).readBytes())
        assertEquals(prior.visualRevision,after.visualRevision)
        assertEquals(prior.activeOcrArtifactRevision,after.activeOcrArtifactRevision)
        assertEquals(prior.ocrText,after.ocrText)
        assertEquals(revision+1,requireNotNull(database.libraryDao().document(saved.id)).contentRevision)
        assertEquals(2,requireNotNull(database.libraryDao().document(saved.id)).contentHashVersion)
        repository.saveSession(reopened,"Synthetic manual").successValue()
        assertEquals(revision+1,requireNotNull(database.libraryDao().document(saved.id)).contentRevision)
        org.junit.Assert.assertArrayEquals(bytes,requireNotNull(fileStore.resolve(database.libraryDao().pages(saved.id)[0].relativePath)).readBytes())
    }

    @Test
    fun individualAndBatchRotationHaveIdenticalExistingOcrInvalidationPolicy() = runBlocking {
        for(batch in listOf(false,true)) {
            val original=session(page(if(batch)21 else 20,if(batch)"batch-ocr.jpg" else "individual-ocr.jpg",0xff406080.toInt()))
            val saved=repository.saveSession(original,"Synthetic rotation",ocrResult=OcrResult(listOf(OcrPageResult(0,"synthetic source OCR")))).successValue()
            val opened=repository.openDocument(saved.id).successValue().session
            val before=database.libraryDao().pages(saved.id).single()
            org.junit.Assert.assertNotNull(before.activeOcrArtifactRevision)
            val changed=if(batch) org.synapseworks.pageharbor.document.batch.prepareBatchEdit(opened,setOf(opened.pages.single().id.value),
                org.synapseworks.pageharbor.document.batch.BatchPageEdit(clockwiseQuarterTurns=1)).session
            else requireNotNull(opened.rotateClockwise(opened.pages.single().id))
            repository.saveSession(changed,"Synthetic rotation").successValue()
            val after=database.libraryDao().pages(saved.id).single()
            assertEquals(before.visualRevision+1,after.visualRevision)
            org.junit.Assert.assertNull(after.activeOcrArtifactRevision);org.junit.Assert.assertNull(after.ocrText)
            assertEquals(before.contentSha256,after.contentSha256)
            org.junit.Assert.assertArrayEquals(requireNotNull(fileStore.resolve(before.relativePath)).readBytes(),requireNotNull(fileStore.resolve(after.relativePath)).readBytes())
        }
    }

    @Test
    fun selectedBatchReopensWithSourceHashesAndUnselectedSettingsIntact() = selectedVisualReopen(false)

    @Test
    fun shadowGlareBatchReopensWithSourceHashesAndUnselectedSettingsIntact() = selectedVisualReopen(true)

    @Test
    fun whiteningColourReopensWithSourceHashesAndUnselectedSettingsIntact() = selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1)
    @Test
    fun whiteningNeutralReopensWithSourceHashesAndUnselectedSettingsIntact() = selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1)

    @Test fun bleedThroughLowReopensWithExactSourceAndManualSettings()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_LOW_V1)
    @Test fun bleedThroughBalancedReopensWithExactSourceAndManualSettings()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_BALANCED_V1)
    @Test fun bleedThroughStrongReopensWithExactSourceAndManualSettings()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_STRONG_V1)

    @Test fun creaseStainLowReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_LOW_V1)
    @Test fun creaseStainBalancedReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_BALANCED_V1)
    @Test fun creaseStainStrongReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_STRONG_V1)

    @Test fun moireLowReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_LOW_V1)
    @Test fun moireBalancedReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_BALANCED_V1)
    @Test fun moireStrongReopensWithSourceManualAndRotation()=selectedVisualReopen(false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_STRONG_V1)

    private fun selectedVisualReopen(shadowGlare:Boolean,whitening:org.synapseworks.pageharbor.image.DocumentFilter?=null) = runBlocking {
        val original = session(page(1,"batch-a.jpg",0xff406080.toInt()),page(2,"batch-b.jpg",0xff808080.toInt()),page(3,"batch-c.jpg",0xff204060.toInt()))
        val saved=repository.saveSession(original,"Synthetic batch").successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val hashes=opened.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
        val revision=requireNotNull(database.libraryDao().document(saved.id)).contentRevision
        val changed=org.synapseworks.pageharbor.document.batch.prepareBatchEdit(opened,setOf(opened.pages[0].id.value,opened.pages[2].id.value),
            org.synapseworks.pageharbor.document.batch.BatchPageEdit(filter=whitening ?: if(shadowGlare)org.synapseworks.pageharbor.image.DocumentFilter.SHADOW_GLARE_BALANCED_V1 else org.synapseworks.pageharbor.image.DocumentFilter.GRAYSCALE,brightness=20,contrast=25,clockwiseQuarterTurns=1)).session
        repository.saveSession(changed,"Synthetic batch").successValue()
        val reopened=repository.openDocument(saved.id).successValue().session
        for(i in reopened.pages.indices) {
            assertEquals(changed.pages[i].persistentId,reopened.pages[i].persistentId)
            assertEquals(changed.pages[i].filter,reopened.pages[i].filter)
            assertEquals(changed.pages[i].rotation,reopened.pages[i].rotation)
            assertEquals(changed.pages[i].adjustments,reopened.pages[i].adjustments)
            org.junit.Assert.assertArrayEquals(hashes[i],context.contentResolver.openInputStream(reopened.pages[i].source.toAndroidUri())!!.use{it.readBytes()})
        }
        if(whitening!=null) {
            val stored=requireNotNull(database.libraryDao().document(saved.id))
            val fingerprint=org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1.calculate(
                database.libraryDao().pages(saved.id).map { record -> org.synapseworks.pageharbor.library.duplicate.FingerprintPage(
                    requireNotNull(record.contentSha256),record.contentType,requireNotNull(record.sourceByteCount),
                    record.rotationDegrees,record.filterName,record.brightness,record.contrast) })
            assertEquals(2,fingerprint.version);assertEquals(fingerprint.version,stored.contentHashVersion)
            assertEquals(fingerprint.sha256,stored.contentSha256)
            assertTrue(fileStore.resolve(requireNotNull(stored.thumbnailRelativePath))?.isFile==true)
        }
        assertEquals(opened.pages[1].adjustments,reopened.pages[1].adjustments)
        assertEquals(opened.pages[1].rotation,reopened.pages[1].rotation)
        assertEquals(revision+1,requireNotNull(database.libraryDao().document(saved.id)).contentRevision)
        repository.saveSession(reopened,"Synthetic batch").successValue()
        assertEquals(revision+1,requireNotNull(database.libraryDao().document(saved.id)).contentRevision)
    }

    @Test
    fun acceptedBookHalvesReopenWithFreshOcrIdentityAndUnchangedPageBytes()=runBlocking {
        val book=org.synapseworks.pageharbor.document.booklayout.BookTestFixtures.source(sourceDirectory,1,2)
            .copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25))
        val original=session(page(1,"book-unaffected.jpg",0xff406080.toInt()),book)
        val saved=repository.saveSession(original,"Synthetic book",ocrResult=OcrResult(listOf(OcrPageResult(0,"unchanged page text"),OcrPageResult(1,"old full spread text")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=opened.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
        val prepared=org.synapseworks.pageharbor.document.booklayout.BookTestFixtures.prepare(context,opened,pageIndex=1)
        try {
            val expected=prepared.after.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
            org.junit.Assert.assertArrayEquals(before[1],context.contentResolver.openInputStream(opened.pages[1].source.toAndroidUri())!!.use{it.readBytes()})
            assertTrue(prepared.after.pages.drop(1).all{it.persistentId==null})
            repository.saveSession(prepared.after,"Synthetic book").successValue()
            val reopened=repository.openDocument(saved.id).successValue().session;assertEquals(3,reopened.pages.size)
            assertEquals(opened.pages[0].persistentId,reopened.pages[0].persistentId)
            org.junit.Assert.assertArrayEquals(before[0],context.contentResolver.openInputStream(reopened.pages[0].source.toAndroidUri())!!.use{it.readBytes()})
            for(i in reopened.pages.indices){org.junit.Assert.assertArrayEquals(expected[i],context.contentResolver.openInputStream(reopened.pages[i].source.toAndroidUri())!!.use{it.readBytes()});assertEquals(prepared.after.pages[i].filter,reopened.pages[i].filter);assertEquals(prepared.after.pages[i].adjustments,reopened.pages[i].adjustments)}
            val rows=database.libraryDao().pages(saved.id);assertEquals("unchanged page text",rows[0].ocrText)
            rows.drop(1).forEach{assertEquals(null,it.activeOcrArtifactRevision);assertEquals(null,it.ocrText)}
        }finally{prepared.close()}
    }

    @Test
    fun acceptedCurvatureReopensWithFreshOcrFingerprintsAndRegeneratedThumbnail()=runBlocking {
        val book=org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.source(sourceDirectory,1,2)
            .copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25))
        val original=session(book,page(1,"curve-unaffected.jpg",0xff406080.toInt()))
        val saved=repository.saveSession(original,"Synthetic book",ocrResult=OcrResult(listOf(OcrPageResult(0,"old curved page text"),OcrPageResult(1,"unchanged page text")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=opened.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
        val priorRecord=requireNotNull(database.libraryDao().document(saved.id))
        val thumbnailBefore=context.contentResolver.openInputStream(repository.thumbnailUri(priorRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
        val prepared=org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.prepare(context,opened,pageIndex=0)
        try {
            val expected=prepared.after.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
            org.junit.Assert.assertArrayEquals(before[0],context.contentResolver.openInputStream(opened.pages[0].source.toAndroidUri())!!.use{it.readBytes()})
            assertTrue(prepared.after.pages.take(1).all{it.persistentId==null})
            repository.saveSession(prepared.after,"Synthetic book").successValue()
            val reopened=repository.openDocument(saved.id).successValue().session;assertEquals(2,reopened.pages.size)
            assertEquals(opened.pages[1].persistentId,reopened.pages[1].persistentId)
            org.junit.Assert.assertArrayEquals(before[1],context.contentResolver.openInputStream(reopened.pages[1].source.toAndroidUri())!!.use{it.readBytes()})
            for(i in reopened.pages.indices){org.junit.Assert.assertArrayEquals(expected[i],context.contentResolver.openInputStream(reopened.pages[i].source.toAndroidUri())!!.use{it.readBytes()});assertEquals(prepared.after.pages[i].filter,reopened.pages[i].filter);assertEquals(prepared.after.pages[i].adjustments,reopened.pages[i].adjustments)}
            val updatedRecord=requireNotNull(database.libraryDao().document(saved.id))
            assertTrue(priorRecord.contentSha256!=updatedRecord.contentSha256)
            assertEquals(2,updatedRecord.contentHashVersion)
            val fingerprint=org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1.calculate(database.libraryDao().pages(saved.id).map{record->
                org.synapseworks.pageharbor.library.duplicate.FingerprintPage(requireNotNull(record.contentSha256),record.contentType,requireNotNull(record.sourceByteCount),record.rotationDegrees,record.filterName,record.brightness,record.contrast)})
            assertEquals(fingerprint.sha256,updatedRecord.contentSha256)
            val thumbnail=context.contentResolver.openInputStream(repository.thumbnailUri(updatedRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
            assertFalse(thumbnailBefore.contentEquals(thumbnail));assertNotNull(android.graphics.BitmapFactory.decodeByteArray(thumbnail,0,thumbnail.size)?.also{it.recycle()})
            val rows=database.libraryDao().pages(saved.id);assertEquals("unchanged page text",rows[1].ocrText)
            rows.take(1).forEach{assertEquals(null,it.activeOcrArtifactRevision);assertEquals(null,it.ocrText)}
        }finally{prepared.close()}
    }

    @Test
    fun acceptedObjectRemovalReopensWithFreshOcrFingerprintsAndRegeneratedThumbnail()=removalRoundTrip(false)
    @Test fun acceptedFingerRemovalReopensWithFreshOcrFingerprintsAndRegeneratedThumbnail()=removalRoundTrip(true)
    private fun removalRoundTrip(finger:Boolean)=runBlocking {
        val book=(if(finger)org.synapseworks.pageharbor.document.finger.FingerFixtures.source(sourceDirectory,2)else org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.source(sourceDirectory,2))
            .copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25))
        val original=session(book,page(1,"curve-unaffected.jpg",0xff406080.toInt()))
        val saved=repository.saveSession(original,"Synthetic book",ocrResult=OcrResult(listOf(OcrPageResult(0,"old curved page text"),OcrPageResult(1,"unchanged page text")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=opened.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
        val priorRecord=requireNotNull(database.libraryDao().document(saved.id))
        val thumbnailBefore=context.contentResolver.openInputStream(repository.thumbnailUri(priorRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
        val prepared=if(finger)org.synapseworks.pageharbor.document.finger.FingerFixtures.prepare(context,opened,pageIndex=0)else org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.prepare(context,opened,pageIndex=0)
        try {
            val expected=prepared.after.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
            org.junit.Assert.assertArrayEquals(before[0],context.contentResolver.openInputStream(opened.pages[0].source.toAndroidUri())!!.use{it.readBytes()})
            assertTrue(prepared.after.pages.take(1).all{it.persistentId==null})
            repository.saveSession(prepared.after,"Synthetic book").successValue()
            val reopened=repository.openDocument(saved.id).successValue().session;assertEquals(2,reopened.pages.size)
            assertEquals(opened.pages[1].persistentId,reopened.pages[1].persistentId)
            org.junit.Assert.assertArrayEquals(before[1],context.contentResolver.openInputStream(reopened.pages[1].source.toAndroidUri())!!.use{it.readBytes()})
            for(i in reopened.pages.indices){org.junit.Assert.assertArrayEquals(expected[i],context.contentResolver.openInputStream(reopened.pages[i].source.toAndroidUri())!!.use{it.readBytes()});assertEquals(prepared.after.pages[i].filter,reopened.pages[i].filter);assertEquals(prepared.after.pages[i].adjustments,reopened.pages[i].adjustments)}
            val updatedRecord=requireNotNull(database.libraryDao().document(saved.id))
            assertTrue(priorRecord.contentSha256!=updatedRecord.contentSha256)
            assertEquals(2,updatedRecord.contentHashVersion)
            val fingerprint=org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1.calculate(database.libraryDao().pages(saved.id).map{record->
                org.synapseworks.pageharbor.library.duplicate.FingerprintPage(requireNotNull(record.contentSha256),record.contentType,requireNotNull(record.sourceByteCount),record.rotationDegrees,record.filterName,record.brightness,record.contrast)})
            assertEquals(fingerprint.sha256,updatedRecord.contentSha256)
            val thumbnail=context.contentResolver.openInputStream(repository.thumbnailUri(updatedRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
            assertFalse(thumbnailBefore.contentEquals(thumbnail));assertNotNull(android.graphics.BitmapFactory.decodeByteArray(thumbnail,0,thumbnail.size)?.also{it.recycle()})
            val rows=database.libraryDao().pages(saved.id);assertEquals("unchanged page text",rows[1].ocrText)
            rows.take(1).forEach{assertEquals(null,it.activeOcrArtifactRevision);assertEquals(null,it.ocrText)}
        }finally{prepared.close()}
    }

    @Test
    fun historyRestoredCurvatureReopensWithFreshOcrFingerprintsAndRegeneratedThumbnail()=runBlocking {
        val book=org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.source(sourceDirectory,1,2)
            .copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(20,-25))
        val original=session(book,page(1,"curve-unaffected.jpg",0xff406080.toInt()))
        val saved=repository.saveSession(original,"Synthetic book",ocrResult=OcrResult(listOf(OcrPageResult(0,"old curved page text"),OcrPageResult(1,"unchanged page text")))).successValue()
        val opened=repository.openDocument(saved.id).successValue().session
        val before=opened.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
        val priorRecord=requireNotNull(database.libraryDao().document(saved.id))
        val thumbnailBefore=context.contentResolver.openInputStream(repository.thumbnailUri(priorRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
        val prepared=org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.prepare(context,opened,pageIndex=0)
        val history=org.synapseworks.pageharbor.document.history.HistoryFixtures.restored(context,prepared)
        try {
            val expected=history.session.pages.map{context.contentResolver.openInputStream(it.source.toAndroidUri())!!.use{stream->stream.readBytes()}}
            org.junit.Assert.assertArrayEquals(before[0],context.contentResolver.openInputStream(opened.pages[0].source.toAndroidUri())!!.use{it.readBytes()})
            assertTrue(history.session.pages.take(1).all{it.persistentId==null})
            repository.saveSession(history.session,"Synthetic book").successValue()
            val reopened=repository.openDocument(saved.id).successValue().session;assertEquals(2,reopened.pages.size)
            assertEquals(opened.pages[1].persistentId,reopened.pages[1].persistentId)
            org.junit.Assert.assertArrayEquals(before[1],context.contentResolver.openInputStream(reopened.pages[1].source.toAndroidUri())!!.use{it.readBytes()})
            for(i in reopened.pages.indices){org.junit.Assert.assertArrayEquals(expected[i],context.contentResolver.openInputStream(reopened.pages[i].source.toAndroidUri())!!.use{it.readBytes()});assertEquals(history.session.pages[i].filter,reopened.pages[i].filter);assertEquals(history.session.pages[i].adjustments,reopened.pages[i].adjustments)}
            val updatedRecord=requireNotNull(database.libraryDao().document(saved.id))
            assertTrue(priorRecord.contentSha256!=updatedRecord.contentSha256)
            assertEquals(2,updatedRecord.contentHashVersion)
            val fingerprint=org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1.calculate(database.libraryDao().pages(saved.id).map{record->
                org.synapseworks.pageharbor.library.duplicate.FingerprintPage(requireNotNull(record.contentSha256),record.contentType,requireNotNull(record.sourceByteCount),record.rotationDegrees,record.filterName,record.brightness,record.contrast)})
            assertEquals(fingerprint.sha256,updatedRecord.contentSha256)
            val thumbnail=context.contentResolver.openInputStream(repository.thumbnailUri(updatedRecord.thumbnailRelativePath)!!)!!.use{it.readBytes()}
            assertFalse(thumbnailBefore.contentEquals(thumbnail));assertNotNull(android.graphics.BitmapFactory.decodeByteArray(thumbnail,0,thumbnail.size)?.also{it.recycle()})
            val rows=database.libraryDao().pages(saved.id);assertEquals("unchanged page text",rows[1].ocrText)
            rows.take(1).forEach{assertEquals(null,it.activeOcrArtifactRevision);assertEquals(null,it.ocrText)}
        }finally{history.close();prepared.close()}
    }

    @Test
    fun saveReopenAndUpdateUseOneEditableSessionModel() = runBlocking {
        val original = session(page(1, "red.jpg", 0xffff0000.toInt()), page(2, "blue.jpg", 0xff0000ff.toInt()))
        val saved = repository.saveSession(
            session = original,
            title = "Field notes",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(0, "alpha term"),
                    OcrPageResult(1, "beta term"),
                ),
            ),
        ).successValue()

        val opened = repository.openDocument(saved.id).successValue()
        assertEquals("Field notes", opened.session.libraryDocument?.title)
        assertEquals(2, opened.session.pages.size)
        assertTrue(opened.session.pages.all { it.persistentId != null })
        assertTrue(opened.session.pages.all {
            it.source.ownership == DocumentResourceOwnership.RME_OWNED_LIBRARY
        })

        val reorderedIds = opened.session.pages.reversed().map(DocumentPage::id)
        val edited = requireNotNull(opened.session.reorder(reorderedIds))
            .rotateClockwise(reorderedIds.first())!!
        repository.saveSession(edited, "Field notes revised").successValue()

        val reopened = repository.openDocument(saved.id).successValue()
        assertEquals("Field notes revised", reopened.summary.title)
        assertEquals(DocumentPageRotation.DEGREES_90, reopened.session.pages.first().rotation)
        assertEquals(opened.session.pages.last().persistentId, reopened.session.pages.first().persistentId)
        assertEquals(listOf(null, "alpha term"), database.libraryDao().pages(saved.id).map { it.ocrText })
    }

    @Test
    fun titleAndOcrSearchAndFolderLifecycleStayLocalAndConsistent() = runBlocking {
        val invoice = repository.saveSession(
            session(page(1, "invoice.jpg", 0xffffffff.toInt())),
            "March invoice",
            ocrResult = OcrResult(listOf(OcrPageResult(0, "consulting services"))),
        ).successValue()
        repository.saveSession(
            session(page(2, "receipt.jpg", 0xffeeeeee.toInt())),
            "Coffee receipt",
        ).successValue()

        assertEquals(invoice.id, repository.observeSearch("march")!!.first().single().id)
        val exactTitle = repository.searchHits("March invoice", limit = 10).first()
        assertEquals(invoice.id, exactTitle.documentId)
        assertEquals(LibrarySearchMatch.TITLE, exactTitle.matchType)
        val ocrMatch = repository.observeSearch("consult")!!.first().single()
        assertEquals(invoice.id, ocrMatch.id)
        assertEquals(LibrarySearchMatch.OCR, ocrMatch.searchMatch)

        val folder = repository.createFolder("Taxes").successValue()
        repository.moveDocument(invoice.id, folder.id).successValue()
        assertEquals(folder.id, repository.openDocument(invoice.id).successValue().summary.folderId)
        val metadataMatch = repository.searchHits("taxes", limit = 10).single()
        assertEquals(invoice.id, metadataMatch.documentId)
        assertEquals(LibrarySearchMatch.FOLDER, metadataMatch.matchType)
        repository.deleteFolder(folder.id).successValue()
        assertEquals(null, repository.openDocument(invoice.id).successValue().summary.folderId)
    }

    @Test
    fun documentDateSortIsDeterministicAndNeverSubstitutesFileOrImportTimes() = runBlocking {
        val dao = database.libraryDao()
        suspend fun insert(
            id: String,
            documentDate: Long?,
            importedAt: Long?,
            sourceModifiedAt: Long?,
        ) {
            dao.replaceDocument(
                LibraryDocumentEntity(
                    documentId = id,
                    title = id,
                    createdAtMillis = 1,
                    modifiedAtMillis = 2,
                    pageCount = 1,
                    folderId = null,
                    thumbnailRelativePath = null,
                    ocrStatus = LibraryOcrStatus.NOT_INDEXED.name,
                    sourceModifiedAtMillis = sourceModifiedAt,
                    importedAtMillis = importedAt,
                    documentDateEpochDay = documentDate,
                    documentDateProvenance = documentDate?.let {
                        LibraryDocumentDateProvenance.USER_CONFIRMED.name
                    },
                    documentDateConfidence = documentDate?.let {
                        LibraryDocumentDateConfidence.EXACT.name
                    },
                ),
                listOf(
                    LibraryPageEntity(
                        pageId = "page-$id",
                        documentId = id,
                        position = 0,
                        relativePath = "$id/page.jpg",
                        contentType = "image/jpeg",
                        sourceCategory = "TEST",
                        width = 10,
                        height = 10,
                        sourceByteCount = 1,
                        rotationDegrees = 0,
                        filterName = "ORIGINAL",
                        ocrText = null,
                        ocrError = null,
                        contentSha256 = "hash-$id",
                    ),
                ),
                "",
            )
        }

        insert("dated-old", documentDate = 10, importedAt = 900, sourceModifiedAt = 1_000)
        insert("dated-new", documentDate = 20, importedAt = 100, sourceModifiedAt = 50)
        insert("unknown-new-import", documentDate = null, importedAt = 800, sourceModifiedAt = 9_999)
        insert("unknown-old-import", documentDate = null, importedAt = 700, sourceModifiedAt = 10_000)
        insert("unknown-no-import", documentDate = null, importedAt = null, sourceModifiedAt = 20_000)

        val ordered = dao.observeDocumentsByDocumentDate(null).first()
        assertEquals(
            listOf(
                "dated-new",
                "dated-old",
                "unknown-new-import",
                "unknown-old-import",
                "unknown-no-import",
            ),
            ordered.map(LibraryDocumentListingRow::documentId),
        )
        val unknown = ordered.associateBy(LibraryDocumentListingRow::documentId)
            .getValue("unknown-no-import")
        assertEquals(null, unknown.documentDateEpochDay)
        assertEquals(null, unknown.documentDateProvenance)
    }

    @Test
    fun boundedSearchKeepsTitleFirstAndEveryStablePageAtItsCurrentPosition() = runBlocking {
        val saved = repository.saveSession(
            session(
                page(10, "first-search.jpg", 0xff112233.toInt()),
                page(11, "second-search.jpg", 0xff223344.toInt()),
                page(12, "third-search.jpg", 0xff334455.toInt()),
            ),
            "Needle contract",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(0, "ordinary first page"),
                    OcrPageResult(1, "needle amount due 42.16"),
                    OcrPageResult(2, "needle invoice date September"),
                ),
            ),
        ).successValue()
        val opened = repository.openDocument(saved.id).successValue().session
        val stableIds = opened.pages.map { requireNotNull(it.persistentId) }

        val initial = repository.searchHits("needle", limit = 10)
        assertEquals(LibrarySearchMatch.TITLE, initial.first().matchType)
        assertEquals(
            listOf(stableIds[1] to 1, stableIds[2] to 2),
            initial.filter { it.matchType == LibrarySearchMatch.OCR }
                .map { it.pageId to it.currentPagePosition },
        )
        assertTrue(initial.filter { it.matchType == LibrarySearchMatch.OCR }.all { hit ->
            hit.snippet?.contains("needle", ignoreCase = true) == true
        })

        val reordered = requireNotNull(
            opened.reorder(listOf(opened.pages[2].id, opened.pages[0].id, opened.pages[1].id)),
        )
        repository.saveSession(reordered, "Needle contract").successValue()

        val afterReorder = repository.searchHits("needle", limit = 10)
            .filter { it.matchType == LibrarySearchMatch.OCR }
        assertEquals(
            listOf(stableIds[2] to 0, stableIds[1] to 2),
            afterReorder.map { it.pageId to it.currentPagePosition },
        )

        val reorderedOpened = repository.openDocument(saved.id).successValue().session
        val withoutFormerThird = requireNotNull(
            reorderedOpened.remove(reorderedOpened.pages.first().id),
        )
        repository.saveSession(withoutFormerThird, "Needle contract").successValue()
        val afterDelete = repository.searchHits("needle", limit = 10)
            .filter { it.matchType == LibrarySearchMatch.OCR }
        assertEquals(listOf(stableIds[1]), afterDelete.map { it.pageId })
        assertEquals(1, afterDelete.single().currentPagePosition)
    }

    @Test
    fun activeBoundedSearchObservesEffectiveOcrCorrectionWithoutRetyping() = runBlocking {
        val saved = repository.saveSession(
            session(page(20, "reactive-search.jpg", 0xff556677.toInt())),
            "Reactive search",
            ocrResult = OcrResult(listOf(OcrPageResult(0, "original searchable phrase"))),
        ).successValue()
        val pageId = database.libraryDao().pages(saved.id).single().pageId
        val snapshot = requireNotNull(database.libraryDao().ocrPageSnapshot(saved.id, pageId))
        val correctedEmission = async {
            repository.observeSearchHits("corrected phrase", limit = 10)!!
                .filter(List<LibrarySearchHit>::isNotEmpty)
                .first()
        }

        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            repository.saveOcrCorrection(
                snapshot,
                LibraryOcrCorrectionDraft(
                    correctedText = "corrected phrase amount 42.16",
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
            ),
        )

        val hits = withTimeout(2_000L) { correctedEmission.await() }
        assertEquals(pageId, hits.single().pageId)
        assertTrue(repository.searchHits("original searchable", limit = 10).isEmpty())
    }

    @Test
    fun firstSavePromotesPositionedOcrToVerifiedPageArtifact() = runBlocking {
        val saved = repository.saveSession(
            session(page(3, "verified-first-save.jpg", 0xffccddee.toInt())),
            "Verified first save",
            ocrResult = OcrResult(
                listOf(
                    OcrPageResult(
                        pageIndex = 0,
                        text = "positioned emerald text",
                        layout = OcrPageLayout(
                            imageWidthPx = 100,
                            imageHeightPx = 200,
                            lines = listOf(
                                OcrTextLine(
                                    text = "positioned emerald text",
                                    bounds = OcrTextBounds(
                                        left = 10f,
                                        top = 20f,
                                        right = 90f,
                                        bottom = 60f,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).successValue()

        val dao = database.libraryDao()
        val page = dao.pages(saved.id).single()
        val snapshot = requireNotNull(dao.ocrPageSnapshot(saved.id, page.pageId))
        val artifact = requireNotNull(
            dao.ocrArtifact(page.pageId, requireNotNull(snapshot.activeArtifactRevision)),
        )
        val line = dao.ocrLines(page.pageId, artifact.artifactRevision).single()

        assertEquals(LibraryOcrArtifactVerification.CURRENT_VERIFIED.name, artifact.verificationState)
        assertEquals("positioned emerald text", artifact.rawText)
        assertEquals(page.contentSha256, artifact.contentSha256)
        assertEquals(snapshot.documentContentRevision, artifact.capturedDocumentContentRevision)
        assertEquals(snapshot.pageVisualRevision, artifact.capturedPageVisualRevision)
        assertEquals(BundledLatinOcrArtifactContract.RECOGNIZER_ID, artifact.recognizerId)
        assertEquals(BundledLatinOcrArtifactContract.PIPELINE_VERSION, artifact.pipelineVersion)
        assertTrue(artifact.inputFingerprint?.matches(Regex("sha256:[0-9a-f]{64}")) == true)
        assertEquals(0.1, line.topLeftX, 0.000_001)
        assertEquals(0.3, line.bottomRightY, 0.000_001)
        assertEquals(
            "positioned emerald text",
            dao.effectiveOcrPage(saved.id, page.pageId)?.effectiveText,
        )
        assertEquals(
            page.pageId,
            dao.pageSearchPage("emerald*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun firstSaveWithoutPositionedGeometryRetainsLegacyRawText() = runBlocking {
        val rawText = "legacy fallback sapphire"
        val saved = repository.saveSession(
            session(page(4, "legacy-first-save.jpg", 0xffbbccdd.toInt())),
            "Legacy first save",
            ocrResult = OcrResult(listOf(OcrPageResult(pageIndex = 0, text = rawText))),
        ).successValue()

        val dao = database.libraryDao()
        val page = dao.pages(saved.id).single()
        val snapshot = requireNotNull(dao.ocrPageSnapshot(saved.id, page.pageId))
        val artifact = requireNotNull(
            dao.ocrArtifact(page.pageId, requireNotNull(snapshot.activeArtifactRevision)),
        )

        assertEquals(LibraryOcrArtifactVerification.LEGACY_UNVERIFIED.name, artifact.verificationState)
        assertEquals(rawText, artifact.rawText)
        assertEquals(rawText, page.ocrText)
        assertEquals(rawText, dao.effectiveOcrPage(saved.id, page.pageId)?.effectiveText)
        assertEquals(
            page.pageId,
            dao.pageSearchPage("sapphire*", Long.MAX_VALUE, "", 10).single().pageId,
        )
    }

    @Test
    fun nestedFoldersKeepParentageAndRejectCycles() = runBlocking {
        val root = repository.createFolder("Projects").successValue()
        val child = repository.createFolder("Receipts", root.id).successValue()
        val grandchild = repository.createFolder("April", child.id).successValue()

        val folders = repository.observeFolders().first().associateBy(LibraryFolder::id)
        assertEquals(root.id, folders.getValue(child.id).parentFolderId)
        assertEquals(child.id, folders.getValue(grandchild.id).parentFolderId)

        val refused = repository.moveFolder(root.id, grandchild.id)
        assertEquals(LibraryError.INVALID_SELECTION, (refused as LibraryResult.Failure).reason)

        repository.deleteFolder(child.id).successValue()
        assertEquals(root.id, database.libraryDao().folder(grandchild.id)?.parentFolderId)
        assertEquals(root.id, database.libraryDao().folder(grandchild.id)?.parentScope)
    }

    @Test
    fun duplicateFolderNamesAreScopedToSiblings() = runBlocking {
        val work = repository.createFolder("Work").successValue()
        val personal = repository.createFolder("Personal").successValue()
        val workYear = repository.createFolder("2024", work.id).successValue()
        val personalYear = repository.createFolder("2024", personal.id).successValue()
        repository.createFolder("2024").successValue()

        val duplicateSibling = repository.createFolder("  2024  ", work.id)
        assertEquals(
            LibraryError.DUPLICATE_FOLDER,
            (duplicateSibling as LibraryResult.Failure).reason,
        )

        val conflictingMove = repository.moveFolder(workYear.id, personal.id)
        assertEquals(
            LibraryError.DUPLICATE_FOLDER,
            (conflictingMove as LibraryResult.Failure).reason,
        )
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentFolderId)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentScope)

        val receipts = repository.createFolder("Receipts", work.id).successValue()
        repository.moveFolder(receipts.id, personal.id).successValue()
        assertEquals(personal.id, database.libraryDao().folder(receipts.id)?.parentFolderId)
        assertEquals(personal.id, database.libraryDao().folder(receipts.id)?.parentScope)
        assertEquals(personal.id, database.libraryDao().folder(personalYear.id)?.parentScope)
    }

    @Test
    fun deletingFolderReparentsNamesSafelyOrReportsSiblingCollision() = runBlocking {
        val archive = repository.createFolder("Archive").successValue()
        val nestedArchive = repository.createFolder("Archive", archive.id).successValue()

        repository.deleteFolder(archive.id).successValue()
        assertEquals(null, database.libraryDao().folder(nestedArchive.id)?.parentFolderId)
        assertEquals("", database.libraryDao().folder(nestedArchive.id)?.parentScope)

        val existingYear = repository.createFolder("2024").successValue()
        val work = repository.createFolder("Work").successValue()
        val workYear = repository.createFolder("2024", work.id).successValue()

        val conflict = repository.deleteFolder(work.id)

        assertEquals(LibraryError.DUPLICATE_FOLDER, (conflict as LibraryResult.Failure).reason)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentFolderId)
        assertEquals(work.id, database.libraryDao().folder(workYear.id)?.parentScope)
        assertEquals(null, database.libraryDao().folder(existingYear.id)?.parentFolderId)
    }

    @Test
    fun streamingSaveHashesPagesAndRetainsDirectPdfAcrossEdits() = runBlocking {
        val documentPage = page(30, "hashed.jpg", 0xff445566.toInt())
        val pdf = File(sourceDirectory, "source.pdf").apply {
            FileOutputStream(this).use { output ->
                output.write("%PDF-1.4\nsynthetic source\n%%EOF".encodeToByteArray())
            }
        }
        val directPdf = DocumentResource(
            reference = Uri.fromFile(pdf).toString(),
            ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
        )
        val saved = repository.saveSession(
            session = DocumentSession(
                pages = listOf(documentPage),
                directPdfSource = directPdf,
                directPdfPageIds = listOf(documentPage.id),
            ),
            title = "Hash proof",
        ).successValue()

        val storedDocument = requireNotNull(database.libraryDao().document(saved.id))
        val storedPage = database.libraryDao().pages(saved.id).single()
        val storedAsset = database.libraryDao().sourceAssets(saved.id).single()
        assertEquals(1, storedDocument.contentHashVersion)
        assertEquals(64, storedDocument.contentSha256?.length)
        assertEquals(storedPage.sourceByteCount, storedDocument.contentByteCount)
        assertEquals(64, storedPage.contentSha256?.length)
        assertEquals(pdf.length(), storedAsset.byteCount)
        assertEquals(64, storedAsset.sha256.length)
        assertTrue(storedAsset.matchesCurrentRevision)

        val opened = repository.openDocument(saved.id).successValue()
        assertNotNull(opened.session.directPdfSource)
        assertTrue(opened.session.canUseDirectPdf)
        val edited = requireNotNull(opened.session.rotateClockwise(opened.session.pages.single().id))
        repository.saveSession(edited, "Hash proof").successValue()

        val reopened = repository.openDocument(saved.id).successValue()
        assertNotNull(reopened.session.directPdfSource)
        assertFalse(reopened.session.canUseDirectPdf)
        assertFalse(database.libraryDao().sourceAssets(saved.id).single().matchesCurrentRevision)
    }

    @Test
    fun multiPdfImportPersistsAndReopensEveryOriginalSource() = runBlocking {
        val originals = (1..2).map { index ->
            val pdf = File(sourceDirectory, "source-$index.pdf").apply {
                FileOutputStream(this).use { output ->
                    output.write("%PDF-1.4\nsynthetic source $index\n%%EOF".encodeToByteArray())
                }
            }
            DocumentResource(
                reference = Uri.fromFile(pdf).toString(),
                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
            )
        }
        val saved = repository.saveSession(
            session = DocumentSession(
                pages = listOf(page(31, "multi-source.jpg", 0xff556677.toInt())),
                originalPdfSources = originals,
            ),
            title = "Multiple originals",
        ).successValue()

        val stored = database.libraryDao().sourceAssets(saved.id)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.role == "ORIGINAL_DOCUMENT" })
        assertTrue(stored.none { it.matchesCurrentRevision })

        val reopened = repository.openDocument(saved.id).successValue().session
        assertEquals(2, reopened.originalPdfSources.size)
        assertEquals(null, reopened.directPdfSource)
    }

    @Test
    fun mergeExtractAndSplitPreserveOrderAndNeverEmptyOriginal() = runBlocking {
        val first = repository.saveSession(
            session(page(1, "first.jpg", 0xff111111.toInt())),
            "First",
        ).successValue()
        val second = repository.saveSession(
            session(page(2, "second.jpg", 0xff222222.toInt())),
            "Second",
        ).successValue()
        val merged = repository.mergeDocuments(listOf(second.id, first.id), "Combined").successValue()
        val mergedPages = database.libraryDao().pages(merged.id)
        assertEquals(2, mergedPages.size)

        val extracted = repository.extractPages(
            merged.id,
            setOf(mergedPages.first().pageId),
            "Extracted",
            removeFromOriginal = false,
        ).successValue()
        assertEquals(1, repository.openDocument(extracted.id).successValue().session.pages.size)
        assertEquals(2, repository.openDocument(merged.id).successValue().session.pages.size)

        val refused = repository.extractPages(
            merged.id,
            mergedPages.mapTo(mutableSetOf()) { it.pageId },
            "Invalid split",
            removeFromOriginal = true,
        )
        assertEquals(LibraryError.EMPTY_DOCUMENT, (refused as LibraryResult.Failure).reason)

        repository.extractPages(
            merged.id,
            setOf(mergedPages.last().pageId),
            "Moved page",
            removeFromOriginal = true,
        ).successValue()
        val remainingPage = repository.openDocument(merged.id).successValue().session.pages.single()
        assertEquals(mergedPages.first().pageId, remainingPage.persistentId)
    }

    @Test
    fun libraryPersistsDocumentsBeyondTheScannerTwentyPageAcquisitionLimit() = runBlocking {
        val pages = (1L..20L).map { index ->
            page(index, "page-$index.jpg", 0xff000000.toInt() or index.toInt())
        }
        val saved = repository.saveSession(DocumentSession(pages), "Twenty pages").successValue()
        assertEquals(20, repository.openDocument(saved.id).successValue().session.pages.size)

        val twentyFirst = pages.first().copy(id = DocumentPageId(21L))
        val expanded = repository.saveSession(DocumentSession(pages + twentyFirst), "Twenty one pages")
            .successValue()
        assertEquals(21, repository.openDocument(expanded.id).successValue().session.pages.size)
    }

    @Test
    fun savedDocumentSurvivesDatabaseCloseAndReopen() = runBlocking {
        val firstDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        val saved = try {
            LibraryRepository(
                context = context,
                dao = firstDatabase.libraryDao(),
                fileStore = LibraryFileStore(context, libraryDirectory),
                nowMillis = { ++clock },
            ).saveSession(
                session(page(1, "restart.jpg", 0xff123456.toInt())),
                title = "Restart proof",
            ).successValue()
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        try {
            val reopened = LibraryRepository(
                context = context,
                dao = reopenedDatabase.libraryDao(),
                fileStore = LibraryFileStore(context, libraryDirectory),
                nowMillis = { ++clock },
            ).openDocument(saved.id).successValue()

            assertEquals("Restart proof", reopened.summary.title)
            assertEquals(1, reopened.session.pages.size)
            assertEquals(
                DocumentResourceOwnership.RME_OWNED_LIBRARY,
                reopened.session.pages.single().source.ownership,
            )
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun renameDeleteAndRevisionCleanupKeepExternalSourceSafeAndIndexConsistent() = runBlocking {
        val externalFile = File(sourceDirectory, "external-safe.jpg")
        val saved = repository.saveSession(
            session(page(1, externalFile.name, 0xff654321.toInt())),
            title = "Original token",
        ).successValue()
        val untouched = repository.saveSession(
            session(page(2, "untouched-document.jpg", 0xff765432.toInt())),
            title = "Untouched document",
        ).successValue()
        val untouchedDirectory = File(libraryDirectory, untouched.id)
        assertTrue(externalFile.isFile)

        val opened = repository.openDocument(saved.id).successValue()
        repository.saveSession(opened.session, "Original token").successValue()
        val revisions = File(libraryDirectory, "${saved.id}/revisions")
        assertEquals(2, revisions.listFiles()?.count { it.isDirectory })

        repository.cleanupDocumentRevisions(saved.id)
        assertEquals(1, revisions.listFiles()?.count { it.isDirectory })

        repository.renameDocument(saved.id, "Renamed token").successValue()
        assertTrue(repository.observeSearch("original")!!.first().isEmpty())
        assertEquals(saved.id, repository.observeSearch("renamed")!!.first().single().id)

        purgeForTest(repository,saved.id).successValue()
        assertEquals(null, database.libraryDao().document(saved.id))
        assertTrue(repository.observeSearch("renamed")!!.first().isEmpty())
        assertFalse(File(libraryDirectory, saved.id).exists())
        assertTrue(externalFile.isFile)
        assertNotNull(database.libraryDao().document(untouched.id))
        assertTrue(untouchedDirectory.isDirectory)
        assertEquals(
            LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND),
            purgeForTest(repository,saved.id),
        )
    }

    @Test
    fun deleteDoesNotReportSuccessWhenPrivateDocumentDirectoryCannotBeRemoved() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "undeletable-private-copy.jpg", 0xff123456.toInt())),
            title = "Deletion failure contract",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        assertTrue(documentDirectory.isDirectory)

        val directories = documentDirectory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
        directories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }

        try {
            val result = purgeForTest(repository,saved.id)

            assertFalse(
                "Deletion reported success while the private document directory still existed",
                result is LibraryResult.Success && documentDirectory.exists(),
            )
            if (result is LibraryResult.Failure) {
                assertNotNull(database.libraryDao().documentAnyState(saved.id))
            }
        } finally {
            directories.asReversed().forEach { directory ->
                directory.setWritable(true, false)
            }
        }
    }

    @Test
    fun committedDeletionReportsDurablePendingCleanupUntilBytesAreRemoved() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "pending-private-cleanup.jpg", 0xff1a2b3c.toInt())),
            title = "Pending private cleanup",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val protectedDirectories = documentDirectory.walkTopDown()
            .filter { candidate -> candidate.isDirectory && candidate != documentDirectory }
            .toList()
            .asReversed()
        protectedDirectories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }

        var pending: PendingDocumentDeletion? = null
        try {
            val result = purgeForTest(repository,saved.id)

            assertTrue("Expected cleanup-pending success but was $result", result is LibraryResult.Success)
            assertEquals(
                LibraryWarning.DELETION_CLEANUP_PENDING,
                (result as LibraryResult.Success).warning,
            )
            assertEquals(null, database.libraryDao().document(saved.id))
            assertFalse(documentDirectory.exists())
            pending = pendingDeletions().single()
        } finally {
            pending?.directory?.walkTopDown()
                ?.filter(File::isDirectory)
                ?.toList()
                ?.asReversed()
                ?.forEach { directory -> directory.setWritable(true, false) }
            protectedDirectories.forEach { directory -> directory.setWritable(true, false) }
        }

        val recovery = repository.recoverPendingDocumentDeletions()
        assertEquals(1, recovery.deleted)
        assertEquals(0, recovery.stillPending)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun databaseDeleteFailureRestoresQuarantinedFilesAndKeepsRecord() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "database-delete-failure.jpg", 0xff234567.toInt())),
            title = "Database failure rollback",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )

        val result = try {
            purgeForTest(repository,saved.id)
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_library_document_delete")
        }

        assertEquals(LibraryResult.Failure(LibraryError.DATABASE_UNAVAILABLE), result)
        assertNotNull(database.libraryDao().documentAnyState(saved.id))
        assertTrue(documentDirectory.isDirectory)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun repeatedDeleteTracksQuarantineWhenDatabaseAndRestorationPreviouslyFailed() = runBlocking {
        var failRestoration = true
        var failCleanup = true
        val failingStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun restoreQuarantinedDocument(pending: PendingDocumentDeletion): Boolean =
                if (failRestoration) false else super.restoreQuarantinedDocument(pending)

            override fun completeDocumentDeletion(pending: PendingDocumentDeletion): Boolean =
                if (failCleanup) false else super.completeDocumentDeletion(pending)
        }
        val failingRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = failingStore,
            nowMillis = { ++clock },
        )
        val saved = failingRepository.saveSession(
            session(page(1, "rollback-and-cleanup-failure.jpg", 0xff334455.toInt())),
            title = "Tracked repeated deletion",
        ).successValue()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_repeated_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )

        val first = try {
            purgeForTest(failingRepository,saved.id)
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_repeated_library_document_delete")
        }

        assertEquals(LibraryResult.Failure(LibraryError.DELETION_RECOVERY_PENDING), first)
        assertNotNull(database.libraryDao().documentAnyState(saved.id))
        assertFalse(File(libraryDirectory, saved.id).exists())
        assertEquals(saved.id, pendingDeletions(failingStore).single().documentId)

        val repeated = purgeForTest(failingRepository,saved.id)
        assertTrue(repeated is LibraryResult.Success)
        assertEquals(
            LibraryWarning.DELETION_CLEANUP_PENDING,
            (repeated as LibraryResult.Success).warning,
        )
        assertEquals(null, database.libraryDao().documentAnyState(saved.id))
        assertEquals(saved.id, pendingDeletions(failingStore).single().documentId)

        failRestoration = false
        failCleanup = false
        val recovered = failingRepository.recoverPendingDocumentDeletions()
        assertEquals(1, recovered.deleted)
        assertEquals(0, recovered.stillPending)
        assertTrue(pendingDeletions(failingStore).isEmpty())
    }

    @Test
    fun transientRestorationFailureRecoversDocumentWithoutDeletingIt() = runBlocking {
        var failRestoration = true
        val failingStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun restoreQuarantinedDocument(pending: PendingDocumentDeletion): Boolean =
                if (failRestoration) false else super.restoreQuarantinedDocument(pending)
        }
        val failingRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = failingStore,
            nowMillis = { ++clock },
        )
        val saved = failingRepository.saveSession(
            session(page(1, "transient-restoration.jpg", 0xff445566.toInt())),
            title = "Transient restoration",
        ).successValue()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER fail_transient_library_document_delete
            BEFORE DELETE ON library_documents
            BEGIN
                SELECT RAISE(ABORT, 'forced deletion failure');
            END
            """.trimIndent(),
        )
        try {
            assertEquals(
                LibraryResult.Failure(LibraryError.DELETION_RECOVERY_PENDING),
                purgeForTest(failingRepository,saved.id),
            )
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_transient_library_document_delete")
        }

        failRestoration = false
        val recovery = failingRepository.recoverPendingDocumentDeletions()
        assertEquals(1, recovery.restored)
        assertEquals(0, recovery.stillPending)
        assertNotNull(database.libraryDao().documentAnyState(saved.id))
        assertTrue(File(libraryDirectory, saved.id).isDirectory)
        assertTrue(pendingDeletions(failingStore).isEmpty())
    }

    @Test
    fun restartRecoveryResolvesBothInterruptionWindows() = runBlocking {
        val persistentStore = LibraryFileStore(context, libraryDirectory)
        var persistentDatabase = Room.databaseBuilder(
            context,
            LibraryDatabase::class.java,
            restartDatabaseName,
        ).build()
        var persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        val saved = persistentRepository.saveSession(
            session(page(1, "interrupted-delete.jpg", 0xff345678.toInt())),
            title = "Interrupted deletion",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)

        val beforeCommit = persistentStore.quarantineDocument(saved.id)
        assertTrue(beforeCommit is DocumentDeletionPreparation.Quarantined)
        assertFalse(documentDirectory.exists())
        persistentDatabase.close()

        persistentDatabase = Room.databaseBuilder(
            context = context,
            klass = LibraryDatabase::class.java,
            name = restartDatabaseName,
        ).build()
        persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        val restored = persistentRepository.recoverPendingDocumentDeletions()
        assertEquals(1, restored.restored)
        assertTrue(documentDirectory.isDirectory)
        assertNotNull(persistentDatabase.libraryDao().document(saved.id))

        val afterCommit = persistentStore.quarantineDocument(saved.id)
        assertTrue(afterCommit is DocumentDeletionPreparation.Quarantined)
        assertNotNull(persistentDatabase.libraryDao().deleteDocument(saved.id))
        persistentDatabase.close()

        persistentDatabase = Room.databaseBuilder(
            context = context,
            klass = LibraryDatabase::class.java,
            name = restartDatabaseName,
        ).build()
        persistentRepository = LibraryRepository(
            context = context,
            dao = persistentDatabase.libraryDao(),
            fileStore = persistentStore,
            nowMillis = { ++clock },
        )
        try {
            val deleted = persistentRepository.recoverPendingDocumentDeletions()
            assertEquals(1, deleted.deleted)
            assertEquals(0, deleted.stillPending)
            assertFalse(documentDirectory.exists())
            assertTrue(pendingDeletions(persistentStore).isEmpty())
        } finally {
            persistentDatabase.close()
        }
    }

    @Test
    fun partialQuarantineCleanupRemainsPendingUntilRetrySucceeds() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "partial-cleanup.jpg", 0xff56789a.toInt())),
            title = "Partial cleanup",
        ).successValue()
        val prepared = fileStore.quarantineDocument(saved.id)
        assertTrue(prepared is DocumentDeletionPreparation.Quarantined)
        val pending = (prepared as DocumentDeletionPreparation.Quarantined).pending
        val directories = pending.directory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
        directories.forEach { directory ->
            assertTrue("Could not make ${directory.name} read-only", directory.setWritable(false, false))
        }
        assertNotNull(database.libraryDao().deleteDocument(saved.id))

        val firstRecovery = repository.recoverPendingDocumentDeletions()
        assertEquals(0, firstRecovery.deleted)
        assertEquals(1, firstRecovery.stillPending)
        assertEquals(saved.id, pendingDeletions().single().documentId)

        pending.directory.walkTopDown()
            .filter(File::isDirectory)
            .toList()
            .asReversed()
            .forEach { directory -> directory.setWritable(true, false) }
        val secondRecovery = repository.recoverPendingDocumentDeletions()
        assertEquals(1, secondRecovery.deleted)
        assertEquals(0, secondRecovery.stillPending)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun legacyConfirmedOrphanIsQuarantinedAndCleanupIsIdempotent() = runBlocking {
        val orphanId = "legacy-confirmed-orphan"
        val orphan = createLegacyDocumentDirectory(orphanId)

        val first = repository.recoverPendingDocumentDeletions()
        assertEquals(1, first.legacyQuarantined)
        assertEquals(1, first.deleted)
        assertEquals(0, first.stillPending)
        assertFalse(orphan.exists())

        val second = repository.recoverPendingDocumentDeletions()
        assertEquals(0, second.legacyQuarantined)
        assertEquals(0, second.deleted)
        assertEquals(0, second.stillPending)
    }

    @Test
    fun legacyDirectoryOwnedByNonterminalMigrationIsPreserved() = runBlocking {
        val documentId = "migration-owned-document"
        val owned = createLegacyDocumentDirectory(documentId)
        val operation = migrationOperation("migration-owner")
        database.libraryDao().insertOperation(operation)
        database.libraryDao().insertOperationItems(
            listOf(migrationItem(operation.operationId, documentId)),
        )

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(0, recovery.legacyQuarantined)
        assertEquals(1, recovery.legacyPreservedOwned)
        assertTrue(owned.isDirectory)
        assertTrue(pendingDeletions().isEmpty())
    }

    @Test
    fun uncertainLegacyStateIsPreservedWhenDatabaseCannotClassifyOwnership() = runBlocking {
        val documentId = "database-uncertain-document"
        val candidate = createLegacyDocumentDirectory(documentId)
        database.close()

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(0, recovery.legacyQuarantined)
        assertEquals(1, recovery.legacyPreservedUncertain)
        assertTrue(recovery.databaseUnavailable)
        assertTrue(candidate.isDirectory)
    }

    @Test
    fun pendingEnumerationFailureIsNotTreatedAsAnEmptyQuarantine() = runBlocking {
        val candidate = createLegacyDocumentDirectory("preserved-when-pending-scan-fails")
        val unavailableStore = object : LibraryFileStore(context, libraryDirectory) {
            override fun pendingDocumentDeletions(): DocumentDirectoryScan<PendingDocumentDeletion> =
                DocumentDirectoryScan.Unavailable
        }
        val unavailableRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = unavailableStore,
            nowMillis = { ++clock },
        )

        val recovery = unavailableRepository.recoverPendingDocumentDeletions()

        assertTrue(recovery.filesystemScanUnavailable)
        assertEquals(0, recovery.legacyQuarantined)
        assertTrue(candidate.isDirectory)
    }

    @Test
    fun mixedLegacyStatesDeleteOnlyConfirmedAbandonedDocumentBytes() = runBlocking {
        val active = repository.saveSession(
            session(page(1, "mixed-active.jpg", 0xff667788.toInt())),
            title = "Mixed active",
        ).successValue()
        val activeDirectory = File(libraryDirectory, active.id)
        val orphan = createLegacyDocumentDirectory("mixed-confirmed-orphan")
        val uncertain = File(libraryDirectory, "mixed-unknown-shape").apply {
            assertTrue(mkdirs())
            File(this, "unrecognized-private-data").writeBytes(byteArrayOf(1, 2, 3))
        }
        val operationOwned = createLegacyDocumentDirectory("mixed-operation-owned")
        val operation = migrationOperation("mixed-migration")
        database.libraryDao().insertOperation(operation)
        database.libraryDao().insertOperationItems(
            listOf(migrationItem(operation.operationId, operationOwned.name)),
        )

        val recovery = repository.recoverPendingDocumentDeletions()

        assertEquals(1, recovery.legacyQuarantined)
        assertEquals(1, recovery.deleted)
        assertEquals(2, recovery.legacyPreservedOwned)
        assertTrue(activeDirectory.isDirectory)
        assertFalse(orphan.exists())
        assertTrue(operationOwned.isDirectory)
        assertTrue(uncertain.isDirectory)
    }

    @Test
    fun deletionWaitsForRealDocumentSessionLeaseBeforeRemovingLibrarySources() = runBlocking {
        val saved = repository.saveSession(
            session(page(1, "leased-library-source.jpg", 0xff778899.toInt())),
            title = "Leased library source",
        ).successValue()
        val opened = repository.openDocument(saved.id).successValue()
        val sessionViewModel = PageHarborSessionViewModel()
        sessionViewModel.installDocumentSessionForTest(opened.session)
        val lease = requireNotNull(sessionViewModel.acquireDocumentSessionLease())
        val source = File(requireNotNull(opened.session.pages.single().source.ownedLibraryFile).path)

        val deletion = async {
            sessionViewModel.awaitDocumentSessionLeasesReleased()
            purgeForTest(repository,saved.id)
        }
        yield()
        assertFalse("Deletion bypassed an active DocumentSessionLease", deletion.isCompleted)
        assertTrue(source.isFile)
        assertNotNull(database.libraryDao().documentAnyState(saved.id))

        sessionViewModel.releaseDocumentSessionLease(lease)
        sessionViewModel.clearScan()
        deletion.await().successValue()
        assertFalse(source.exists())
        assertEquals(null, database.libraryDao().documentAnyState(saved.id))
    }

    @Test
    fun deletionMessagesDistinguishPendingCleanupAndPendingRestoration() {
        assertEquals(
            R.string.library_delete_cleanup_pending,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Succeeded(
                    eventId = 1,
                    action = LibraryActionSuccess.DELETED,
                    warning = LibraryWarning.DELETION_CLEANUP_PENDING,
                ),
            ),
        )
        assertEquals(
            R.string.library_error_deletion_recovery_pending,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Failed(2, LibraryError.DELETION_RECOVERY_PENDING),
            ),
        )
        assertEquals(
            R.string.library_error_deletion_state_uncertain,
            org.synapseworks.pageharbor.ui.home.libraryActionMessageResource(
                LibraryActionState.Failed(3, LibraryError.DELETION_STATE_UNCERTAIN),
            ),
        )
    }

    @Test
    fun deletionWaitsForActiveStableRevisionSnapshot() = runBlocking {
        val gate = LibraryOperationGate()
        val gatedRepository = LibraryRepository(
            context = context,
            dao = database.libraryDao(),
            fileStore = fileStore,
            nowMillis = { ++clock },
            operationGate = gate,
        )
        val saved = gatedRepository.saveSession(
            session(page(1, "stable-snapshot.jpg", 0xff456789.toInt())),
            title = "Stable snapshot",
        ).successValue()
        val documentDirectory = File(libraryDirectory, saved.id)
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val snapshot = async {
            gate.withStableSnapshot {
                assertTrue(documentDirectory.isDirectory)
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                assertTrue(documentDirectory.isDirectory)
            }
        }
        snapshotStarted.await()

        val deletion = async { purgeForTest(gatedRepository,saved.id) }
        yield()
        assertFalse("Deletion bypassed the active revision snapshot", deletion.isCompleted)
        releaseSnapshot.complete(Unit)
        snapshot.await()
        deletion.await().successValue()

        assertFalse(documentDirectory.exists())
        assertEquals(null, database.libraryDao().document(saved.id))
    }

    /** Compatibility adapter for the pre-bin permanent-deletion regression assertions. */
    private suspend fun purgeForTest(repo:LibraryRepository,id:String):LibraryResult<Unit> {
        val row=database.libraryDao().documentAnyState(id)?:return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        if(row.libraryState=="ACTIVE") {
            val moved=repo.deleteDocument(id);if(moved is LibraryResult.Failure)return moved
        }
        val trashed=database.libraryDao().documentAnyState(id)?:return LibraryResult.Failure(LibraryError.DOCUMENT_NOT_FOUND)
        return when(val r=repo.permanentlyDelete(id,trashed.trashRevision)) {
            is org.synapseworks.pageharbor.library.recycle.RecycleResult.Done->LibraryResult.Success(Unit,if(r.cleanupPending)LibraryWarning.DELETION_CLEANUP_PENDING else null)
            is org.synapseworks.pageharbor.library.recycle.RecycleResult.Failed->LibraryResult.Failure(when(r.reason){
                org.synapseworks.pageharbor.library.recycle.RecycleFailure.STORAGE->LibraryError.STORAGE_UNAVAILABLE
                org.synapseworks.pageharbor.library.recycle.RecycleFailure.STATE_UNCERTAIN->LibraryError.DELETION_RECOVERY_PENDING
                org.synapseworks.pageharbor.library.recycle.RecycleFailure.CHANGED,org.synapseworks.pageharbor.library.recycle.RecycleFailure.NOT_FOUND->LibraryError.DOCUMENT_NOT_FOUND
                else->LibraryError.DATABASE_UNAVAILABLE
            })
        }
    }

    private fun session(vararg pages: DocumentPage): DocumentSession =
        DocumentSession(pages = pages.toList())

    private fun page(id: Long, name: String, color: Int): DocumentPage {
        val source = File(sourceDirectory, name)
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        bitmap.recycle()
        return DocumentPage(
            id = DocumentPageId(id),
            source = DocumentResource(
                reference = Uri.fromFile(source).toString(),
                ownership = DocumentResourceOwnership.USER_OR_EXTERNAL,
            ),
            sourceCategory = DocumentSourceCategory.SELECTED_IMAGE,
            imageMetadata = DocumentImageMetadata(source.length(), 48, 64),
        )
    }

    private fun <T> LibraryResult<T>.successValue(): T {
        assertTrue("Expected success but was $this", this is LibraryResult.Success)
        return (this as LibraryResult.Success).value
    }

    private fun pendingDeletions(
        store: LibraryFileStore = fileStore,
    ): List<PendingDocumentDeletion> =
        when (val scan = store.pendingDocumentDeletions()) {
            is DocumentDirectoryScan.Available -> scan.entries
            DocumentDirectoryScan.Unavailable -> error("Pending-deletion directory was unavailable")
        }

    private fun createLegacyDocumentDirectory(documentId: String): File {
        val revision = File(libraryDirectory, "$documentId/revisions/revision-1")
        assertTrue(revision.mkdirs())
        File(revision, "page.jpg").writeBytes(byteArrayOf(1, 2, 3, 4))
        return File(libraryDirectory, documentId)
    }

    private fun migrationOperation(operationId: String) = LibraryDataOperationEntity(
        operationId = operationId,
        operationType = "VERIFIED_MIGRATION",
        phase = "STAGING",
        sourceKind = "SAF_TREE",
        sourceRootUri = null,
        sourceGrantFlags = 0,
        createdAtMillis = ++clock,
        updatedAtMillis = clock,
        discoveredItemCount = 1,
        plannedDocumentCount = 1,
        preparedDocumentCount = 0,
        importedDocumentCount = 0,
        skippedDuplicateCount = 0,
        failedItemCount = 0,
        totalSourceBytes = 4,
        processedSourceBytes = 0,
        cancelRequested = false,
        terminalErrorCode = null,
    )

    private fun migrationItem(
        operationId: String,
        documentId: String,
    ) = LibraryDataOperationItemEntity(
        itemId = "$operationId-item",
        operationId = operationId,
        ordinal = 0,
        itemState = "STAGING",
        targetDocumentId = documentId,
        targetRevisionId = null,
        proposedTitle = "Migration candidate",
        proposedFolderPath = null,
        sourceModifiedAtMillis = null,
        sourceByteCount = 4,
        sourcePageCount = 1,
        logicalHashVersion = null,
        logicalSha256 = null,
        duplicateKind = "DIFFERENT",
        duplicateDocumentId = null,
        duplicateDecision = "IMPORT",
        failureCode = null,
    )
}
