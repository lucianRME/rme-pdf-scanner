package org.synapseworks.pageharbor.backup.restore

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.backup.engine.FileLibraryBackupWorkspace
import org.synapseworks.pageharbor.backup.engine.LibraryBackupClock
import org.synapseworks.pageharbor.backup.engine.LibraryBackupCreationResult
import org.synapseworks.pageharbor.backup.engine.LibraryBackupEngine
import org.synapseworks.pageharbor.backup.engine.LibraryBackupIdSource
import org.synapseworks.pageharbor.backup.engine.LibraryBackupStoragePreflight
import org.synapseworks.pageharbor.backup.engine.RoomLibraryBackupSnapshotSource
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.publish.BackupPublicationDestination
import org.synapseworks.pageharbor.backup.publish.BackupPublicationEngine
import org.synapseworks.pageharbor.backup.publish.BackupPublicationResult
import org.synapseworks.pageharbor.backup.publish.PublishedBackupKind
import org.synapseworks.pageharbor.document.session.DocumentImageMetadata
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import org.synapseworks.pageharbor.document.session.DocumentSourceCategory
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.library.LibraryDao
import org.synapseworks.pageharbor.library.LibraryDatabase
import org.synapseworks.pageharbor.library.LibraryDocumentEntity
import org.synapseworks.pageharbor.library.LibraryDocumentDateConfidence
import org.synapseworks.pageharbor.library.LibraryDocumentDateProvenance
import org.synapseworks.pageharbor.library.LibraryEffectiveOcrPage
import org.synapseworks.pageharbor.library.LibraryFileTimeProvenance
import org.synapseworks.pageharbor.library.LibraryFileStore
import org.synapseworks.pageharbor.library.LibraryFolderEntity
import org.synapseworks.pageharbor.library.LibraryOcrArtifactDraft
import org.synapseworks.pageharbor.library.LibraryOcrCommitResult
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionAlignment
import org.synapseworks.pageharbor.library.LibraryOcrCorrectionDraft
import org.synapseworks.pageharbor.library.LibraryOcrLineDraft
import org.synapseworks.pageharbor.library.LibraryOcrStatus
import org.synapseworks.pageharbor.library.LibraryOperationGate
import org.synapseworks.pageharbor.library.LibraryPageEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrArtifactEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrCorrectionEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrCorrectionLineEntity
import org.synapseworks.pageharbor.library.LibraryPageOcrLineEntity
import org.synapseworks.pageharbor.library.LibraryPageSource
import org.synapseworks.pageharbor.library.LibraryRepository
import org.synapseworks.pageharbor.library.LibraryResult
import org.synapseworks.pageharbor.library.LibrarySourceAssetEntity
import org.synapseworks.pageharbor.library.LibrarySourceAssetSource
import org.synapseworks.pageharbor.library.LibrarySourceProvenanceKind
import org.synapseworks.pageharbor.library.LibraryTextSourceKind
import org.synapseworks.pageharbor.library.duplicate.DocumentFingerprintV1
import org.synapseworks.pageharbor.library.duplicate.FingerprintPage
import org.synapseworks.pageharbor.portability.export.PortableExportPdfWriteResult
import org.synapseworks.pageharbor.portability.export.RoomPortableExportPdfSource

@RunWith(AndroidJUnit4::class)
class PortableBackupRestoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun plainAndEncryptedBackupsRestoreEquivalentRealRoomLibraries() = runBlocking {
        val runId = UUID.randomUUID().toString()
        val testRoot = File(context.filesDir, "document-library/portable-round-trip-$runId")
        val workRoot = File(context.cacheDir, "portable-round-trip-$runId")
        val source = LibraryRig.create(context, File(testRoot, "source"), "source-$runId.db")
        val plain = LibraryRig.create(context, File(testRoot, "plain"), "plain-$runId.db")
        val encrypted = LibraryRig.create(context, File(testRoot, "encrypted"), "encrypted-$runId.db")
        val password = "portable-test-password-ș".toCharArray()
        // M35 private offers, source grants/names and queue snapshots are not portable data.
        val privateCanary = "M35_PRIVATE_WORKFLOW_NOT_PORTABLE_$runId".toByteArray()
        val privateFiles = listOf("workflow-v1", "office-offers", "office-ui", "office-preview").map { name ->
            File(context.noBackupFilesDir, name).apply { check(mkdirs() || isDirectory) }
                .resolve("m35-private-$runId.control").apply { check(!exists()); writeBytes(privateCanary) }
        }

        try {
            seedSourceLibrary(source)
            val expected = logicalSnapshot(source)

            val backupResult = LibraryBackupEngine(
                snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                workspace = FileLibraryBackupWorkspace(File(workRoot, "backup-workspace")),
                operationGate = LibraryOperationGate(),
                storagePreflight = LibraryBackupStoragePreflight { true },
                clock = LibraryBackupClock { BACKUP_CREATED_AT },
                idSource = LibraryBackupIdSource { BACKUP_ID },
            ).create(
                BackupProducer(
                    applicationId = context.packageName,
                    versionName = "1.5.0",
                    versionCode = 16,
                ),
            )
            val artifact = (backupResult as LibraryBackupCreationResult.Verified).artifact
            try {
                assertEquals(2, artifact.manifest.summary.documentCount)
                assertEquals(28, artifact.manifest.summary.pageCount)
                assertEquals(2, artifact.manifest.summary.folderCount)
                assertEquals(1, artifact.manifest.summary.sourceAssetCount)

                val plainFile = File(workRoot, "published/rme-backup.zip")
                val encryptedFile = File(workRoot, "published/rme-backup.rmebackup")
                val publication = BackupPublicationEngine()
                val plainPublished = publication.publishUnencrypted(
                    artifact,
                    FileBackupDestination(plainFile),
                ) as BackupPublicationResult.Verified
                val encryptedPublished = publication.publishEncrypted(
                    artifact,
                    FileBackupDestination(encryptedFile),
                    password,
                ) as BackupPublicationResult.Verified

                assertEquals(PublishedBackupKind.UNENCRYPTED_ZIP, plainPublished.backup.kind)
                assertEquals(PublishedBackupKind.ENCRYPTED_ENVELOPE_V1, encryptedPublished.backup.kind)
                assertEquals(artifact.manifest, plainPublished.backup.manifest)
                assertEquals(artifact.manifest, encryptedPublished.backup.manifest)
                assertTrue(plainFile.isFile && plainFile.length() > 0L)
                assertTrue(encryptedFile.isFile && encryptedFile.length() > 0L)
                assertFalse(encryptedFile.readBytes().containsSubsequence(OCR_SENTINEL.toByteArray()))
                java.util.zip.ZipInputStream(plainFile.inputStream()).use { zip ->
                    while (zip.nextEntry != null) {
                        assertFalse(zip.readBytes().containsSubsequence(privateCanary))
                        zip.closeEntry()
                    }
                }
                assertFalse(encryptedFile.readBytes().containsSubsequence(privateCanary))


                assertRestoreFailureAndRecoveryCleanup(
                    plainRig = plain,
                    encryptedRig = encrypted,
                    plainBackup = plainFile,
                    encryptedBackup = encryptedFile,
                    workRoot = File(workRoot, "failure-cleanup"),
                )

                restoreAndAssert(
                    rig = plain,
                    backup = plainFile,
                    password = null,
                    expectedKind = RestoreInputKind.ZIP,
                    expected = expected,
                    workRoot = File(workRoot, "plain-restore"),
                    idSeed = 100,
                )
                restoreAndAssert(
                    rig = encrypted,
                    backup = encryptedFile,
                    password = password,
                    expectedKind = RestoreInputKind.ENCRYPTED,
                    expected = expected,
                    workRoot = File(workRoot, "encrypted-restore"),
                    idSeed = 500,
                )
            } finally {
                artifact.close()
            }
        } finally {
            privateFiles.forEach { check(!it.exists() || it.delete()) }
            password.fill('\u0000')
            source.close()
            plain.close()
            encrypted.close()
            testRoot.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    @Test
    fun manualParametersRoundTripInPlainAndEncryptedBackups() = visualParametersRoundTrip(false)

    @Test
    fun selectedBatchParametersRoundTripInPlainAndEncryptedBackups() = visualParametersRoundTrip(true)

    @Test
    fun shadowGlareParametersRoundTripInPlainAndEncryptedBackups() = visualParametersRoundTrip(true,true)

    @Test
    fun whiteningColourRoundTripsPlainAndEncryptedBackups() = visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1)
    @Test
    fun whiteningNeutralRoundTripsPlainAndEncryptedBackups() = visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1)

    @Test
    fun bookHalvesRoundTripPlainAndEncryptedBackups()=visualParametersRoundTrip(false,book=true)

    @Test
    fun curvedPageRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(false,curvature=true)

    @Test
    fun historyRestoredCurvatureRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(false,curvature=true,history=true)

    @Test fun editableAnnotationsRoundTripPlainAndEncryptedBackups()=visualParametersRoundTrip(false,annotations=true)

    @Test fun placedSignaturesRoundTripPlainAndEncryptedBackups()=visualParametersRoundTrip(false,annotations=true,signatures=true)

    @Test fun collagePagesRoundTripPlainAndEncryptedBackups()=visualParametersRoundTrip(false,collage=true)

    @Test fun organizationRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(false,organization=true)

    @Test fun bleedThroughLowRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_LOW_V1)
    @Test fun bleedThroughBalancedRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_BALANCED_V1)
    @Test fun bleedThroughStrongRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.BLEED_THROUGH_STRONG_V1)

    @Test fun creaseStainLowRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_LOW_V1)
    @Test fun creaseStainBalancedRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_BALANCED_V1)
    @Test fun creaseStainStrongRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.CREASE_STAIN_STRONG_V1)

    @Test fun moireLowRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_LOW_V1)
    @Test fun moireBalancedRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_BALANCED_V1)
    @Test fun moireStrongRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(true,false,org.synapseworks.pageharbor.image.DocumentFilter.MOIRE_STRONG_V1)

    @Test fun acceptedObjectRemovalRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(false,removal=true)
    @Test fun acceptedFingerRemovalRoundTripsPlainAndEncryptedBackups()=visualParametersRoundTrip(false,removal=true,finger=true)

    private fun visualParametersRoundTrip(batch: Boolean,shadowGlare:Boolean=false,whitening:org.synapseworks.pageharbor.image.DocumentFilter?=null,book:Boolean=false,curvature:Boolean=false,history:Boolean=false,annotations:Boolean=false,signatures:Boolean=false,collage:Boolean=false,organization:Boolean=false,removal:Boolean=false,finger:Boolean=false) = runBlocking {
        val runId = UUID.randomUUID().toString()
        val testRoot = File(context.filesDir, "document-library/portable-round-trip-$runId")
        val workRoot = File(context.cacheDir, "portable-round-trip-$runId")
        val source = LibraryRig.create(context, File(testRoot, "source"), "source-$runId.db")
        val plain = LibraryRig.create(context, File(testRoot, "plain"), "plain-$runId.db")
        val encrypted = LibraryRig.create(context, File(testRoot, "encrypted"), "encrypted-$runId.db")
        val password = "portable-test-password-ș".toCharArray()

        var preparedRemoval:org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.Prepared?=null
        var preparedCollage:org.synapseworks.pageharbor.document.collage.CollageFixtures.Prepared?=null
        var preparedBook:org.synapseworks.pageharbor.document.booklayout.BookTestFixtures.Prepared?=null
        var restoredHistory:org.synapseworks.pageharbor.document.history.HistoryFixtures.Restored?=null
        var preparedCurvature:org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.Prepared?=null
        val templateRoot=if(signatures)File(context.noBackupFilesDir,"synthetic-backup-assets-$runId")else null
        try {
            if(templateRoot!=null)org.synapseworks.pageharbor.document.signature.SignatureTemplateStore(templateRoot).save("Synthetic reusable-only sentinel",org.synapseworks.pageharbor.document.signature.SignatureFixtures.ink())
            seedSourceLibrary(source)
            val orgIds=if(organization){
                val org=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(source.dao)
                val id=(org.create("Synthetic catalog",SHORT_DOCUMENT_ID) as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved).tagId!!
                org.create("Unused catalog");org.favorite(SHORT_DOCUMENT_ID,true)
                listOf(id)
            }else emptyList()
            val localOrganizationIds=mutableMapOf<LibraryRig,String>()
            if(organization)for(rig in listOf(plain,encrypted)) {
                val org=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(rig.dao)
                localOrganizationIds[rig]=(org.create("SYNTHETIC CATALOG") as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved).tagId!!
            }
            val repository=LibraryRepository(context,source.dao,source.fileStore)
            val opened=(repository.openDocument(SHORT_DOCUMENT_ID) as LibraryResult.Success).value.session
            val changed=if(removal){
                val input=(if(finger)org.synapseworks.pageharbor.document.finger.FingerFixtures.source(File(workRoot,"removal-input").apply{mkdirs()},opened.pages[0].id.value)else org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.source(File(workRoot,"removal-input").apply{mkdirs()},opened.pages[0].id.value))
                    .copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,rotation=opened.pages[0].rotation,adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35),annotations=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer())
                preparedRemoval=if(finger)org.synapseworks.pageharbor.document.finger.FingerFixtures.prepare(context,opened.copy(pages=listOf(input)+opened.pages.drop(1)))else org.synapseworks.pageharbor.document.objectremoval.ObjectRemovalFixtures.prepare(context,opened.copy(pages=listOf(input)+opened.pages.drop(1)))
                preparedRemoval!!.after
            }else if(collage){
                val edited=opened.copy(pages=listOf(opened.pages[0].copy(annotations=org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer(),adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35)))+opened.pages.drop(1))
                preparedCollage=org.synapseworks.pageharbor.document.collage.CollageFixtures.prepare(context,edited)
                preparedCollage!!.after
            }else if(annotations){
                opened.copy(pages=listOf(opened.pages[0].copy(annotations=if(signatures)org.synapseworks.pageharbor.document.signature.SignatureFixtures.layer()else org.synapseworks.pageharbor.document.annotation.AnnotationFixtures.layer(),adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35)))+opened.pages.drop(1))
            }else if(book){
                val edited=opened.copy(pages=listOf(opened.pages[0].copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,
                    adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35)))+opened.pages.drop(1))
                preparedBook=org.synapseworks.pageharbor.document.booklayout.BookTestFixtures.prepare(context,edited,manual=org.synapseworks.pageharbor.document.booklayout.BookSplitLine(.4f,.42f))
                preparedBook!!.after
            }else if(curvature){
                val edited=opened.copy(pages=listOf(opened.pages[0].copy(filter=org.synapseworks.pageharbor.image.DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,
                    adjustments=org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35)))+opened.pages.drop(1))
                preparedCurvature=org.synapseworks.pageharbor.document.curvature.CurvatureFixtures.prepare(context,edited,
                    manual=org.synapseworks.pageharbor.document.curvature.CurvatureField.manual(listOf(.04f,0f,-.05f,0f,.04f),.04f))
                if(history){restoredHistory=org.synapseworks.pageharbor.document.history.HistoryFixtures.restored(context,preparedCurvature!!);restoredHistory!!.session}else preparedCurvature!!.after
            }else if(batch) org.synapseworks.pageharbor.document.batch.prepareBatchEdit(opened,
                setOf(opened.pages.first().id.value,opened.pages.last().id.value),
                org.synapseworks.pageharbor.document.batch.BatchPageEdit(filter=whitening ?: if(shadowGlare)org.synapseworks.pageharbor.image.DocumentFilter.SHADOW_GLARE_BALANCED_V1 else null,brightness=-20,contrast=35,clockwiseQuarterTurns=1)).session
            else requireNotNull(opened.setAdjustments(opened.pages[0].id,org.synapseworks.pageharbor.image.ManualImageAdjustments(-20,35)))
            if(batch) {
                assertEquals(opened.pages.first().rotation.clockwise(),changed.pages.first().rotation)
                assertEquals(opened.pages.last().rotation.clockwise(),changed.pages.last().rotation)
                opened.pages.drop(1).dropLast(1).forEach { page -> assertEquals(page,changed.pages.first{it.id==page.id}) }
            }
            check(repository.saveSession(changed,SHORT_DOCUMENT_TITLE) is LibraryResult.Success)
            assertEquals(-20,source.dao.pages(SHORT_DOCUMENT_ID)[0].brightness)
            assertEquals(35,source.dao.pages(SHORT_DOCUMENT_ID)[0].contrast)
            val expected = logicalSnapshot(source)

            val backupResult = LibraryBackupEngine(
                snapshotSource = RoomLibraryBackupSnapshotSource(source.dao, source.fileStore),
                workspace = FileLibraryBackupWorkspace(File(workRoot, "backup-workspace")),
                operationGate = LibraryOperationGate(),
                storagePreflight = LibraryBackupStoragePreflight { true },
                clock = LibraryBackupClock { BACKUP_CREATED_AT },
                idSource = LibraryBackupIdSource { BACKUP_ID },
            ).create(
                BackupProducer(
                    applicationId = context.packageName,
                    versionName = "1.5.0",
                    versionCode = 16,
                ),
            )
            val artifact = (backupResult as LibraryBackupCreationResult.Verified).artifact
            try {
                assertEquals(2, artifact.manifest.summary.documentCount)
                assertEquals(if(collage)30 else if(book)29 else 28, artifact.manifest.summary.pageCount)
                assertEquals(2, artifact.manifest.summary.folderCount)
                assertEquals(1, artifact.manifest.summary.sourceAssetCount)

                val plainFile = File(workRoot, "published/rme-backup.zip")
                val encryptedFile = File(workRoot, "published/rme-backup.rmebackup")
                val publication = BackupPublicationEngine()
                val plainPublished = publication.publishUnencrypted(
                    artifact,
                    FileBackupDestination(plainFile),
                ) as BackupPublicationResult.Verified
                val encryptedPublished = publication.publishEncrypted(
                    artifact,
                    FileBackupDestination(encryptedFile),
                    password,
                ) as BackupPublicationResult.Verified

                assertEquals(PublishedBackupKind.UNENCRYPTED_ZIP, plainPublished.backup.kind)
                assertEquals(PublishedBackupKind.ENCRYPTED_ENVELOPE_V1, encryptedPublished.backup.kind)
                assertEquals(artifact.manifest, plainPublished.backup.manifest)
                assertEquals(artifact.manifest, encryptedPublished.backup.manifest)
                assertTrue(plainFile.isFile && plainFile.length() > 0L)
                assertTrue(encryptedFile.isFile && encryptedFile.length() > 0L)
                assertFalse(encryptedFile.readBytes().containsSubsequence(OCR_SENTINEL.toByteArray()))
                if(signatures)java.util.zip.ZipFile(plainFile).use{zip->
                    zip.entries().asSequence().forEach{entry->
                        assertFalse(entry.name.endsWith(".rmesignatures"))
                        if(entry.name.endsWith(".jsonl")||entry.name.endsWith(".json"))assertFalse(zip.getInputStream(entry).bufferedReader().use{it.readText()}.contains("Synthetic reusable-only sentinel"))
                    }
                    assertTrue(File(templateRoot,"templates.rmesignatures").isFile)
                }

                assertRestoreFailureAndRecoveryCleanup(
                    plainRig = plain,
                    encryptedRig = encrypted,
                    plainBackup = plainFile,
                    encryptedBackup = encryptedFile,
                    workRoot = File(workRoot, "failure-cleanup"),
                )

                restoreAndAssert(
                    rig = plain,
                    backup = plainFile,
                    password = null,
                    expectedKind = RestoreInputKind.ZIP,
                    expected = expected,
                    workRoot = File(workRoot, "plain-restore"),
                    idSeed = 100,
                    expectedEmbeddedOcr = !(batch||book||curvature||removal),
                )
                restoreAndAssert(
                    rig = encrypted,
                    backup = encryptedFile,
                    password = password,
                    expectedKind = RestoreInputKind.ENCRYPTED,
                    expected = expected,
                    workRoot = File(workRoot, "encrypted-restore"),
                    idSeed = 500,
                    expectedEmbeddedOcr = !(batch||book||curvature||removal),
                )
                if(organization)for(rig in listOf(plain,encrypted)) {
                    val catalog=rig.dao.organizationTags()
                    assertEquals(setOf("SYNTHETIC CATALOG","Unused catalog"),catalog.map{it.name}.toSet())
                    assertEquals(localOrganizationIds[rig],catalog.single{it.normalizedName=="synthetic catalog"}.tagId)
                    assertTrue(catalog.none{it.tagId in orgIds})
                    val docs=rig.dao.activeDocumentsPage(-1,10)
                    val restored=docs.single{it.title==SHORT_DOCUMENT_TITLE}
                    assertTrue(restored.isFavorite)
                    assertEquals(listOf(catalog.single{it.normalizedName=="synthetic catalog"}.tagId),rig.dao.documentTagIds(restored.documentId))
                    val other=docs.single{it.documentId!=restored.documentId};assertFalse(other.isFavorite);assertTrue(rig.dao.documentTagIds(other.documentId).isEmpty())
                }
                if(collage)for(rig in listOf(plain,encrypted)) {
                    val doc=rig.dao.activeDocumentsPage(-1,100).single{it.title==SHORT_DOCUMENT_TITLE}
                    assertEquals(4,doc.contentHashVersion)
                    val reopened=(LibraryRepository(context,rig.dao,rig.fileStore).openDocument(doc.documentId) as LibraryResult.Success).value.session
                    assertEquals(changed.pages.map{it.paperSize},reopened.pages.map{it.paperSize})
                    assertEquals(2,reopened.pages.count{it.paperSize!=null})
                    assertTrue(reopened.pages.takeLast(2).all{it.annotations.isEmpty&&it.adjustments.isNeutral})
                    val stream=ByteArrayOutputStream()
                    assertEquals(PortableExportPdfWriteResult.WRITTEN,RoomPortableExportPdfSource(context,rig.dao,rig.fileStore).writePdf(doc.documentId,stream))
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream.toByteArray()).use{pdf->
                        assertEquals(changed.pages.size,pdf.numberOfPages)
                        assertEquals(595.2756f,pdf.getPage(pdf.numberOfPages-1).mediaBox.width,.002f)
                        assertEquals(841.8898f,pdf.getPage(pdf.numberOfPages-1).mediaBox.height,.002f)
                    }
                }
                if(removal)for(rig in listOf(plain,encrypted)) {
                    val doc=rig.dao.activeDocumentsPage(-1,100).single{it.title==SHORT_DOCUMENT_TITLE}
                    val reopened=(LibraryRepository(context,rig.dao,rig.fileStore).openDocument(doc.documentId) as LibraryResult.Success).value.session
                    val actual=reopened.pages.first()
                    val accepted=changed.pages.first()
                    assertEquals("image/png",actual.contentType)
                    assertEquals(accepted.rotation,actual.rotation)
                    assertEquals(accepted.filter,actual.filter)
                    assertEquals(accepted.adjustments,actual.adjustments)
                    assertEquals(accepted.annotations,actual.annotations)
                    assertTrue(context.contentResolver.openInputStream(Uri.parse(actual.source.reference))!!.use{it.readBytes()}.contentEquals(
                        context.contentResolver.openInputStream(Uri.parse(accepted.source.reference))!!.use{it.readBytes()}))
                    assertNull(rig.dao.effectiveOcrPage(doc.documentId,rig.dao.pages(doc.documentId).first().pageId))
                }
                if(annotations)for(rig in listOf(plain,encrypted)) {
                    val docs=rig.dao.activeDocumentsPage(-1,100)
                    val layers=docs.flatMap{rig.dao.pages(it.documentId)}.map{it.annotations}.filter{it.isNotEmpty()}
                    assertEquals(1,layers.size)
                    assertEquals(changed.pages[0].annotations,org.synapseworks.pageharbor.document.annotation.AnnotationLayer.decode(layers.single()))
                    assertTrue(docs.any{it.contentHashVersion==3})
                }
            } finally {
                artifact.close()
            }
        } finally {
            templateRoot?.deleteRecursively()
            preparedRemoval?.close()
            preparedCollage?.close()
            preparedBook?.close()
            restoredHistory?.close()
            preparedCurvature?.close()
            password.fill('\u0000')
            source.close()
            plain.close()
            encrypted.close()
            testRoot.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    private suspend fun assertRestoreFailureAndRecoveryCleanup(
        plainRig: LibraryRig,
        encryptedRig: LibraryRig,
        plainBackup: File,
        encryptedBackup: File,
        workRoot: File,
    ) {
        val wrongPasswordRoot = File(workRoot, "wrong-password")
        val wrongPasswordReference = RestoreSafReference("wrong-password")
        val wrongPasswordCoordinator = restoreCoordinator(
            rig = encryptedRig,
            safAccess = FileRestoreSafAccess(wrongPasswordReference, encryptedBackup),
            workRoot = wrongPasswordRoot,
            idSeed = 700,
        )
        val wrongPassword = wrongPasswordCoordinator.prepare(
            wrongPasswordReference,
            "definitely-wrong".toCharArray(),
        )
        wrongPassword as RestoreCoordinatorPrepareResult.Failed
        assertEquals(
            RestoreCoordinatorFailure.WRONG_PASSWORD_OR_DAMAGED_ENCRYPTED_BACKUP,
            wrongPassword.reason,
        )
        assertRestoreScratchEmpty(wrongPasswordRoot)
        assertTrue(encryptedRig.dao.activeDocumentsPage(-1L, 10).isEmpty())

        val cancellationRoot = File(workRoot, "cancelled-copy")
        val cancellationReference = RestoreSafReference("cancelled-copy")
        val cancellationCoordinator = restoreCoordinator(
            rig = plainRig,
            safAccess = CancelAfterSniffRestoreSafAccess(cancellationReference, plainBackup),
            workRoot = cancellationRoot,
            idSeed = 800,
        )
        var cancellationPropagated = false
        try {
            cancellationCoordinator.prepare(cancellationReference)
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue("Restore cancellation was not propagated", cancellationPropagated)
        assertRestoreScratchEmpty(cancellationRoot)
        assertTrue(plainRig.dao.activeDocumentsPage(-1L, 10).isEmpty())

        val recoveryRoot = File(workRoot, "interrupted-recovery")
        val operationId = "00000000-0000-4000-8000-000000000900"
        val orphanId = "00000000-0000-4000-8000-000000000999"
        val store = RoomRestoreLibraryStore(
            context = context,
            dao = plainRig.dao,
            fileStore = plainRig.fileStore,
            clock = RestoreClock { RESTORED_AT },
        )
        val workspace = FileRestoreStagingWorkspace(recoveryRoot)
        store.beginOperation(
            RestoreJournalPlan(
                operationId = operationId,
                backupId = BACKUP_ID,
                createdAtEpochMillis = RESTORED_AT,
                contentByteLength = 0L,
                discoveredDocumentCount = 0,
                plannedDocumentCount = 0,
                skippedExactDocumentCount = 0,
            ),
        )
        assertTrue(File(recoveryRoot, operationId).mkdirs())
        assertTrue(File(recoveryRoot, orphanId).mkdirs())
        val recovered = LibraryRestoreEngine(
            store = store,
            stagingWorkspace = workspace,
            operationGate = LibraryOperationGate(),
            storagePreflight = RestoreStoragePreflight { true },
            idSource = SequentialRestoreIdSource(900),
            clock = RestoreClock { RESTORED_AT },
        ).recoverInterruptedOperations()

        assertEquals(1, recovered)
        assertEquals("FAILED", plainRig.dao.operation(operationId)?.phase)
        assertTrue(store.recoverableOperations().isEmpty())
        assertTrue(recoveryRoot.listFiles().isNullOrEmpty())
        assertTrue(plainRig.dao.activeDocumentsPage(-1L, 10).isEmpty())
    }

    private fun assertRestoreScratchEmpty(workRoot: File) {
        assertTrue(File(workRoot, "verified-staging").listFiles().isNullOrEmpty())
        assertTrue(File(workRoot, "archive-staging").listFiles().isNullOrEmpty())
    }

    @Test fun organizationRestoreConflictsSkipExactAndCapacityFailureAreAtomic()=organizationRestoreConflicts(false)
    @Test fun encryptedOrganizationRestoreConflictsSkipExactAndCapacityFailureAreAtomic()=organizationRestoreConflicts(true)
    private fun organizationRestoreConflicts(encrypted:Boolean)=runBlocking {
        val id=UUID.randomUUID().toString()
        val root=File(context.filesDir,"document-library/org-conflicts-$id")
        val work=File(context.cacheDir,"org-conflicts-$id")
        val source=LibraryRig.create(context,File(root,"source"),"org-source-$id.db")
        val target=LibraryRig.create(context,File(root,"target"),"org-target-$id.db")
        val full=LibraryRig.create(context,File(root,"full"),"org-full-$id.db")
        val password="synthetic-conflict-password".toCharArray()
        try {
            seedSourceLibrary(source);seedSourceLibrary(target)
            val sourceOrg=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(source.dao)
            val incoming=(sourceOrg.create("Archive",SHORT_DOCUMENT_ID) as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved).tagId!!
            sourceOrg.create("Unused incoming");sourceOrg.favorite(SHORT_DOCUMENT_ID,true)
            val targetOrg=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(target.dao)
            val local=(targetOrg.create("ARCHIVE") as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved).tagId!!
            val other=(targetOrg.create("Local only",SHORT_DOCUMENT_ID) as org.synapseworks.pageharbor.library.organization.OrganizationResult.Saved).tagId!!
            // Incoming ID equality must not hijack an unrelated local tag.
            target.database.openHelper.writableDatabase.execSQL("INSERT INTO library_tags(tag_id,name,normalized_name) VALUES(?,?,?)",arrayOf(incoming,"Unrelated local","unrelated local"))
            val before=logicalSnapshot(target)
            val artifact=(LibraryBackupEngine(RoomLibraryBackupSnapshotSource(source.dao,source.fileStore),FileLibraryBackupWorkspace(File(work,"backup")),operationGate=LibraryOperationGate(),storagePreflight=LibraryBackupStoragePreflight{true}).create(BackupProducer(context.packageName,"1.7.2",21)) as LibraryBackupCreationResult.Verified).artifact
            try {
                val backup=File(work,"published.zip")
                val publication=BackupPublicationEngine()
                val published=if(encrypted)publication.publishEncrypted(artifact,FileBackupDestination(backup),password)
                    else publication.publishUnencrypted(artifact,FileBackupDestination(backup))
                assertTrue(published is BackupPublicationResult.Verified)
                suspend fun restore(rig:LibraryRig,policy:RestoreMergePolicy,seed:Int):RestoreResult {
                    val ref=RestoreSafReference("organization-conflict")
                    val c=restoreCoordinator(rig,FileRestoreSafAccess(ref,backup),File(work,"restore-$seed"),seed)
                    val prepared=(c.prepare(ref,if(encrypted)password else null) as RestoreCoordinatorPrepareResult.Ready).prepared
                    return c.restore(prepared,policy)
                }
                val skip=restore(target,RestoreMergePolicy.MERGE_SKIP_EXACT,700) as RestoreResult.Completed
                assertEquals(0,skip.importedDocumentCount);assertEquals(2,skip.skippedExactDocumentCount)
                val afterSkip=logicalSnapshot(target)
                assertEquals(before.documents,afterSkip.documents)
                assertTrue(afterSkip.folders.containsAll(before.folders)) // Existing restore policy allocates collision-named backup folders even when documents skip.
                assertFalse(target.dao.document(SHORT_DOCUMENT_ID)!!.isFavorite)
                assertEquals(listOf(other),target.dao.documentTagIds(SHORT_DOCUMENT_ID))
                assertEquals(local,target.dao.organizationTags().single{it.normalizedName=="archive"}.tagId)
                assertTrue(target.dao.organizationTags().any{it.name=="Unused incoming"})
                val imported=restore(target,RestoreMergePolicy.MERGE_IMPORT_ANYWAY,900) as RestoreResult.Completed
                assertEquals(2,imported.importedDocumentCount)
                val copy=target.dao.activeDocumentsPage(-1,10).single{it.title==SHORT_DOCUMENT_TITLE&&it.documentId!=SHORT_DOCUMENT_ID}
                assertTrue(copy.isFavorite);assertEquals(listOf(local),target.dao.documentTagIds(copy.documentId))
                assertEquals(listOf(other),target.dao.documentTagIds(SHORT_DOCUMENT_ID))
                val fullOrg=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(full.dao)
                repeat(256){fullOrg.create("Capacity $it")}
                val failed=restore(full,RestoreMergePolicy.MERGE_IMPORT_ANYWAY,1100)
                assertTrue(failed is RestoreResult.Failed)
                assertTrue(full.dao.activeDocumentsPage(-1,10).isEmpty());assertTrue(full.dao.foldersPage(0,10).isEmpty())
                assertEquals(256,full.dao.organizationTags().size)
                assertTrue(full.fileStore.root.walkTopDown().none{it.isFile})
                full.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM library_pending_restore_tags").use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
                assertTrue(File(work,"restore-1100/verified-staging").listFiles().isNullOrEmpty())
                for(rig in listOf(target,full))rig.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
            } finally { artifact.close() }
        } finally {password.fill('\u0000');source.close();target.close();full.close();root.deleteRecursively();work.deleteRecursively()}
    }

    @Test fun plainRecycleBackupExclusionAndDeletedIdentityConflicts()=recycleBackup(false)
    @Test fun encryptedRecycleBackupExclusionAndDeletedIdentityConflicts()=recycleBackup(true)
    private fun recycleBackup(encrypted:Boolean)=runBlocking {
        val id=UUID.randomUUID().toString()
        val root=File(context.filesDir,"document-library/recycle-backup-$id")
        val work=File(context.cacheDir,"recycle-backup-$id")
        val source=LibraryRig.create(context,File(root,"source"),"recycle-source-$id.db")
        val target=LibraryRig.create(context,File(root,"target"),"recycle-target-$id.db")
        val password="synthetic-recycle-password".toCharArray()
        try {
            seedSourceLibrary(source)
            val organization=org.synapseworks.pageharbor.library.organization.LibraryOrganizationRepository(source.dao)
            organization.create("Recycle retained",SHORT_DOCUMENT_ID);organization.favorite(SHORT_DOCUMENT_ID,true)
            val before=logicalSnapshot(source)
            suspend fun backup(label:String,expected:Int):File {
                val artifact=(LibraryBackupEngine(RoomLibraryBackupSnapshotSource(source.dao,source.fileStore),FileLibraryBackupWorkspace(File(work,label)),operationGate=LibraryOperationGate(),storagePreflight=LibraryBackupStoragePreflight{true}).create(BackupProducer(context.packageName,"1.7.2",21)) as LibraryBackupCreationResult.Verified).artifact
                try {
                    assertEquals(expected,artifact.manifest.summary.documentCount)
                    val file=File(work,"$label.archive")
                    val publication=BackupPublicationEngine()
                    val r=if(encrypted)publication.publishEncrypted(artifact,FileBackupDestination(file),password)else publication.publishUnencrypted(artifact,FileBackupDestination(file))
                    assertTrue(r is BackupPublicationResult.Verified);return file
                }finally{artifact.close()}
            }
            var sequence=2000
            fun coordinator(rig:LibraryRig,file:File):Pair<AndroidRestoreCoordinator,RestoreSafReference>{
                val ref=RestoreSafReference("synthetic-recycle-backup");sequence+=100
                return restoreCoordinator(rig,FileRestoreSafAccess(ref,file),File(work,"restore-$sequence"),sequence) to ref
            }
            suspend fun blocked(rig:LibraryRig,file:File){
                for(policy in RestoreMergePolicy.entries){
                    val(c,ref)=coordinator(rig,file)
                    val r=c.prepare(ref,if(encrypted)password else null)
                    assertEquals(RestoreCoordinatorFailure.DELETED_DOCUMENT_CONFLICT,(r as RestoreCoordinatorPrepareResult.Failed).reason)
                }
            }
            val old=backup("before-trash",2)
            val repo=LibraryRepository(context,source.dao,source.fileStore)
            assertTrue(repo.deleteDocument(SHORT_DOCUMENT_ID) is LibraryResult.Success)
            val trash=source.dao.recycleDocument(SHORT_DOCUMENT_ID)!!
            blocked(source,old)
            val active=backup("after-trash",1)
            val(activeCoordinator,activeRef)=coordinator(target,active)
            val prepared=(activeCoordinator.prepare(activeRef,if(encrypted)password else null) as RestoreCoordinatorPrepareResult.Ready).prepared
            assertEquals(1,prepared.preview.documentCount)
            assertEquals(1,(activeCoordinator.restore(prepared,RestoreMergePolicy.MERGE_IMPORT_ANYWAY) as RestoreResult.Completed).importedDocumentCount)
            val imported=target.dao.activeDocumentsPage(-1,10).single()
            assertEquals(LONG_DOCUMENT_TITLE,imported.title)
            assertTrue(target.dao.organizationTags().any{it.name=="Recycle retained"})
            assertEquals(org.synapseworks.pageharbor.library.recycle.RecycleResult.Done(),repo.restoreFromRecycle(SHORT_DOCUMENT_ID,trash.trashRevision))
            assertEquals(before,logicalSnapshot(source))
            // A deletion after preview must reject the entire restore before creating pending rows.
            val(c,ref)=coordinator(source,old)
            val ready=(c.prepare(ref,if(encrypted)password else null) as RestoreCoordinatorPrepareResult.Ready).prepared
            repo.deleteDocument(SHORT_DOCUMENT_ID)
            val failed=c.restore(ready,RestoreMergePolicy.MERGE_IMPORT_ANYWAY) as RestoreResult.Failed
            assertEquals(RestoreFailure.DELETED_DOCUMENT_CONFLICT,failed.reason)
            assertEquals(1,source.dao.activeDocumentsPage(-1,10).size)
            source.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM library_documents WHERE library_state='PENDING'").use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            val again=source.dao.recycleDocument(SHORT_DOCUMENT_ID)!!
            assertEquals(org.synapseworks.pageharbor.library.recycle.RecycleResult.Done(),repo.permanentlyDelete(SHORT_DOCUMENT_ID,again.trashRevision))
            blocked(source,old)
            // Remapped restored IDs retain their archive origin even after purge.
            val targetRepo=LibraryRepository(context,target.dao,target.fileStore)
            // An unrelated local identity with the same current trash content also blocks restore.
            target.database.openHelper.writableDatabase.execSQL("DELETE FROM library_document_origins WHERE document_id=?",arrayOf(imported.documentId))
            targetRepo.deleteDocument(imported.documentId)
            assertFalse(target.dao.isDeletedArchiveIdentity(LONG_DOCUMENT_ID))
            blocked(target,active)
            targetRepo.restoreFromRecycle(imported.documentId,target.dao.recycleDocument(imported.documentId)!!.trashRevision)
            target.dao.rememberArchiveOrigin(org.synapseworks.pageharbor.library.recycle.DocumentArchiveOrigin(imported.documentId,LONG_DOCUMENT_ID))
            targetRepo.deleteDocument(imported.documentId)
            val targetTrash=target.dao.recycleDocument(imported.documentId)!!
            assertTrue(target.dao.isDeletedArchiveIdentity(LONG_DOCUMENT_ID))
            assertEquals(org.synapseworks.pageharbor.library.recycle.RecycleResult.Done(),targetRepo.restoreFromRecycle(imported.documentId,targetTrash.trashRevision))
            assertFalse(target.dao.isDeletedArchiveIdentity(LONG_DOCUMENT_ID))
            targetRepo.deleteDocument(imported.documentId)
            targetRepo.permanentlyDelete(imported.documentId,target.dao.recycleDocument(imported.documentId)!!.trashRevision)
            blocked(target,active)
            for(rig in listOf(source,target))rig.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        }finally{
            // These independent synthetic libraries intentionally reuse archive IDs. Release only
            // this fixture's process-local ID block; its durable marker assertions ran above.
            org.synapseworks.pageharbor.library.recycle.LibrarySourceReaders.unblock(SHORT_DOCUMENT_ID)
            password.fill('\u0000');source.close();target.close();root.deleteRecursively();work.deleteRecursively()
        }
    }

    private suspend fun restoreAndAssert(
        rig: LibraryRig,
        backup: File,
        password: CharArray?,
        expectedKind: RestoreInputKind,
        expected: LogicalLibrary,
        workRoot: File,
        idSeed: Int,
        expectedEmbeddedOcr: Boolean = true,
    ) {
        assertTrue(rig.dao.activeDocumentsPage(-1L, 10).isEmpty())
        assertTrue(rig.dao.foldersPage(0, 10).isEmpty())
        val reference = RestoreSafReference("test-backup")
        val coordinator = restoreCoordinator(
            rig = rig,
            safAccess = FileRestoreSafAccess(reference, backup),
            workRoot = workRoot,
            idSeed = idSeed,
        )

        assertEquals(
            RestoreSourceInspection.Supported(expectedKind, password != null),
            coordinator.inspect(reference),
        )
        // Preview and the later user-approved restore run in distinct launcher Jobs in production.
        val preparation = coroutineScope {
            async { coordinator.prepare(reference, password) }.await()
        }
        val prepared = when (preparation) {
            is RestoreCoordinatorPrepareResult.Ready -> preparation.prepared
            is RestoreCoordinatorPrepareResult.Failed -> error(
                "Restore preparation failed: ${preparation.reason}/${preparation.detailCode}",
            )
            RestoreCoordinatorPrepareResult.PasswordRequired ->
                error("Restore preparation unexpectedly required a password")
        }
        assertEquals(2, prepared.preview.documentCount)
        assertEquals(expected.documents.sumOf{it.pages.size}, prepared.preview.pageCount)
        assertEquals(2, prepared.preview.folderCount)
        assertEquals(1, prepared.preview.sourceAssetCount)

        val result = coroutineScope {
            async { coordinator.restore(prepared, RestoreMergePolicy.MERGE_IMPORT_ANYWAY) }.await()
        }

        result as RestoreResult.Completed
        assertEquals(2, result.importedDocumentCount)
        assertEquals(0, result.skippedExactDocumentCount)
        assertTrue(result.stagingCleanupSucceeded)
        assertEquals(expected, logicalSnapshot(rig))
        assertTrue(
            rig.dao.activeDocumentsPage(-1L, 10)
                .flatMap { document -> rig.dao.pages(document.documentId) }
                .none { page ->
                    page.pageId.startsWith("$LONG_DOCUMENT_ID-page-") ||
                        page.pageId.startsWith("$SHORT_DOCUMENT_ID-page-")
                },
        )
        assertDerivedStateAndExport(rig, workRoot, expectedEmbeddedOcr)
        assertTrue(File(workRoot, "verified-staging").listFiles().isNullOrEmpty())
        assertTrue(File(workRoot, "archive-staging").listFiles().isNullOrEmpty())
    }

    private fun restoreCoordinator(
        rig: LibraryRig,
        safAccess: RestoreSafAccess,
        workRoot: File,
        idSeed: Int,
    ): AndroidRestoreCoordinator = AndroidRestoreCoordinator(
        engine = LibraryRestoreEngine(
            store = RoomRestoreLibraryStore(
                context = context,
                dao = rig.dao,
                fileStore = rig.fileStore,
                clock = RestoreClock { RESTORED_AT },
            ),
            stagingWorkspace = FileRestoreStagingWorkspace(File(workRoot, "verified-staging")),
            operationGate = LibraryOperationGate(),
            storagePreflight = RestoreStoragePreflight { true },
            idSource = SequentialRestoreIdSource(idSeed),
            clock = RestoreClock { RESTORED_AT },
        ),
        safAccess = safAccess,
        archiveWorkspace = FileRestoreArchiveWorkspace(File(workRoot, "archive-staging")),
        archivePreflight = RestoreStoragePreflight { true },
        testing = Unit,
    )

    private suspend fun assertDerivedStateAndExport(rig: LibraryRig, workRoot: File, expectedEmbeddedOcr: Boolean) {
        val repository = LibraryRepository(context, rig.dao, rig.fileStore)
        val search = requireNotNull(repository.observeSearch("quartz")).first()
        assertEquals(listOf(LONG_DOCUMENT_TITLE), search.map { it.title })
        val importedSearch = requireNotNull(repository.observeSearch("obsidian")).first()
        assertEquals(if (expectedEmbeddedOcr) listOf(SHORT_DOCUMENT_TITLE) else emptyList<String>(), importedSearch.map { it.title })

        val documents = rig.dao.activeDocumentsPage(-1L, 10)
        assertEquals(2, documents.size)
        documents.forEach { document ->
            val thumbnail = requireNotNull(
                rig.fileStore.resolve(requireNotNull(document.thumbnailRelativePath)),
            )
            assertTrue(thumbnail.isFile && thumbnail.length() > 0L)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(thumbnail.path, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        }

        val longDocument = documents.single { it.title == LONG_DOCUMENT_TITLE }
        val firstPage = rig.dao.pages(longDocument.documentId).first()
        val effective = requireNotNull(rig.dao.effectiveOcrPage(longDocument.documentId, firstPage.pageId))
        assertEquals("private amber archive sentinel\nsecond original line", effective.rawText)
        assertEquals("$OCR_SENTINEL\nsecond corrected line", effective.effectiveText)
        assertEquals(listOf(OCR_SENTINEL, "second corrected line"), effective.lines.map { it.text })
        assertEquals(LibraryOcrCorrectionAlignment.LINE_ALIGNED, effective.alignment)
        assertEquals("CURRENT_VERIFIED", effective.verification.name)
        val source = rig.dao.sourceAssets(longDocument.documentId).single()
        val sourceFile = requireNotNull(rig.fileStore.resolve(source.relativePath))
        assertTrue(sourceFile.isFile && sourceFile.length() == source.byteCount)
        assertTrue(sourceFile.inputStream().use { input ->
            val signature = ByteArray(5)
            input.read(signature) == signature.size && signature.contentEquals("%PDF-".toByteArray())
        })
        assertEquals(LONG_PAGE_COUNT, pdfPageCount(sourceFile))

        val shortDocument = documents.single { it.title == SHORT_DOCUMENT_TITLE }
        val embeddedPage = rig.dao.pages(shortDocument.documentId).first()
        if (expectedEmbeddedOcr) {
            val embedded = requireNotNull(
                rig.dao.effectiveOcrPage(shortDocument.documentId, embeddedPage.pageId),
            )
            assertEquals("embedded obsidian original", embedded.rawText)
            assertEquals(EMBEDDED_CORRECTION_SENTINEL, embedded.effectiveText)
            assertEquals(LibraryTextSourceKind.PDF_EMBEDDED_TEXT, embedded.textSourceKind)
            assertTrue(embedded.lines.isEmpty())
        } else {
            // Rotation follows the pre-existing single-page visual-revision policy.
            // A stale OCR/correction must not become current again after restore.
            assertEquals(null, rig.dao.effectiveOcrPage(shortDocument.documentId, embeddedPage.pageId))
            assertEquals(null, embeddedPage.activeOcrArtifactRevision)
        }

        val export = ByteArrayOutputStream()
        assertEquals(
            PortableExportPdfWriteResult.WRITTEN,
            RoomPortableExportPdfSource(context, rig.dao, rig.fileStore)
                .writePdf(longDocument.documentId, export),
        )
        val exportedBytes = export.toByteArray()
        assertTrue(exportedBytes.size > 5)
        assertArrayEquals("%PDF-".toByteArray(), exportedBytes.copyOfRange(0, 5))
        val exportedFile = File(workRoot, "ordinary-export.pdf").apply { writeBytes(exportedBytes) }
        assertEquals(LONG_PAGE_COUNT, pdfPageCount(exportedFile))
    }

    private suspend fun seedSourceLibrary(rig: LibraryRig) {
        rig.dao.insertFolder(
            LibraryFolderEntity(
                folderId = ROOT_FOLDER_ID,
                name = "Archive",
                normalizedName = "archive",
                createdAtMillis = ROOT_CREATED_AT,
                modifiedAtMillis = ROOT_MODIFIED_AT,
            ),
        )
        rig.dao.insertFolder(
            LibraryFolderEntity(
                folderId = CHILD_FOLDER_ID,
                name = "2026",
                normalizedName = "2026",
                createdAtMillis = CHILD_CREATED_AT,
                modifiedAtMillis = CHILD_MODIFIED_AT,
                parentFolderId = ROOT_FOLDER_ID,
            ),
        )

        val originalPdf = File(rig.fileStore.root.parentFile, "synthetic-original.pdf")
        writePdf(originalPdf, LONG_PAGE_COUNT)
        seedDocument(
            rig = rig,
            documentId = LONG_DOCUMENT_ID,
            title = LONG_DOCUMENT_TITLE,
            folderId = CHILD_FOLDER_ID,
            createdAt = LONG_CREATED_AT,
            modifiedAt = LONG_MODIFIED_AT,
            pageCount = LONG_PAGE_COUNT,
            sourcePdf = originalPdf,
        )
        seedDocument(
            rig = rig,
            documentId = SHORT_DOCUMENT_ID,
            title = SHORT_DOCUMENT_TITLE,
            folderId = ROOT_FOLDER_ID,
            createdAt = SHORT_CREATED_AT,
            modifiedAt = SHORT_MODIFIED_AT,
            pageCount = SHORT_PAGE_COUNT,
            sourcePdf = null,
        )
        seedVerifiedOcrWithCorrection(rig)
        seedEmbeddedTextWithCorrection(rig)
        originalPdf.delete()
    }

    private suspend fun seedEmbeddedTextWithCorrection(rig: LibraryRig) {
        val page = rig.dao.pages(SHORT_DOCUMENT_ID).first()
        val expected = requireNotNull(rig.dao.ocrPageSnapshot(SHORT_DOCUMENT_ID, page.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            rig.dao.commitOcrArtifact(
                expected = expected,
                draft = LibraryOcrArtifactDraft(
                    inputFingerprintVersion = 1,
                    inputFingerprint = "sha256:embedded-input",
                    contentSha256 = requireNotNull(expected.contentSha256),
                    rotationDegrees = expected.rotationDegrees,
                    filterName = expected.filterName,
                    uprightWidth = PAGE_WIDTH,
                    uprightHeight = PAGE_HEIGHT,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                    actualScript = null,
                    recognizerId = "PDF_TEXT_LAYER_V1",
                    pipelineVersion = "PDF_TEXT_LAYER_V1",
                    clientVersion = null,
                    delivery = "LOCAL_PDFBOX",
                    recognizedAtMillis = SHORT_MODIFIED_AT + 1,
                    rawText = "embedded obsidian original",
                    textSourceKind = LibraryTextSourceKind.PDF_EMBEDDED_TEXT,
                    sourceDescriptor = "pdf-sha256:instrumentation:page:0:extractor:PDF_TEXT_LAYER_V1",
                ),
                modifiedAt = SHORT_MODIFIED_AT + 1,
            ),
        )
        val recognized = requireNotNull(rig.dao.ocrPageSnapshot(SHORT_DOCUMENT_ID, page.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            rig.dao.saveOcrCorrection(
                expected = recognized,
                correction = LibraryOcrCorrectionDraft(
                    correctedText = EMBEDDED_CORRECTION_SENTINEL,
                    alignment = LibraryOcrCorrectionAlignment.FREEFORM,
                ),
                modifiedAt = SHORT_MODIFIED_AT + 2,
            ),
        )
    }

    private suspend fun seedVerifiedOcrWithCorrection(rig: LibraryRig) {
        val page = rig.dao.pages(LONG_DOCUMENT_ID).first()
        val expected = requireNotNull(rig.dao.ocrPageSnapshot(LONG_DOCUMENT_ID, page.pageId))
        val rawLines = listOf(
            positionedLine(0, "private amber archive sentinel", 0.10, 0.25),
            positionedLine(1, "second original line", 0.35, 0.50),
        )
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            rig.dao.commitOcrArtifact(
                expected = expected,
                draft = LibraryOcrArtifactDraft(
                    inputFingerprintVersion = 1,
                    inputFingerprint = "sha256:verified-ocr-input",
                    contentSha256 = requireNotNull(expected.contentSha256),
                    rotationDegrees = expected.rotationDegrees,
                    filterName = expected.filterName,
                    uprightWidth = PAGE_WIDTH,
                    uprightHeight = PAGE_HEIGHT,
                    coordinateSystemVersion = 1,
                    transformVersion = 1,
                    actualScript = "LATIN",
                    recognizerId = "ML_KIT_LATIN_BUNDLED",
                    pipelineVersion = "instrumentation-v1",
                    clientVersion = "16.0.1",
                    delivery = "BUNDLED",
                    recognizedAtMillis = LONG_MODIFIED_AT + 1,
                    rawText = rawLines.joinToString("\n", transform = LibraryOcrLineDraft::rawText),
                    lines = rawLines,
                ),
                modifiedAt = LONG_MODIFIED_AT + 1,
            ),
        )
        val recognized = requireNotNull(rig.dao.ocrPageSnapshot(LONG_DOCUMENT_ID, page.pageId))
        assertEquals(
            LibraryOcrCommitResult.APPLIED,
            rig.dao.saveOcrCorrection(
                expected = recognized,
                correction = LibraryOcrCorrectionDraft(
                    correctedText = "$OCR_SENTINEL\nsecond corrected line",
                    alignment = LibraryOcrCorrectionAlignment.LINE_ALIGNED,
                    correctedLines = listOf(OCR_SENTINEL, "second corrected line"),
                ),
                modifiedAt = LONG_MODIFIED_AT + 2,
            ),
        )
    }

    private fun positionedLine(
        ordinal: Int,
        text: String,
        top: Double,
        bottom: Double,
    ) = LibraryOcrLineDraft(
        lineOrdinal = ordinal,
        rawText = text,
        topLeftX = 0.10,
        topLeftY = top,
        topRightX = 0.90,
        topRightY = top,
        bottomRightX = 0.90,
        bottomRightY = bottom,
        bottomLeftX = 0.10,
        bottomLeftY = bottom,
        baselineStartX = 0.10,
        baselineStartY = bottom,
        baselineEndX = 0.90,
        baselineEndY = bottom,
        baselineAngleDegrees = 0.0,
        writingOrientation = "HORIZONTAL",
    )

    private suspend fun seedDocument(
        rig: LibraryRig,
        documentId: String,
        title: String,
        folderId: String,
        createdAt: Long,
        modifiedAt: Long,
        pageCount: Int,
        sourcePdf: File?,
    ) {
        val pageBytes = List(pageCount) { index -> pngPage(index, documentId == LONG_DOCUMENT_ID) }
        val pageSources = pageBytes.mapIndexed { index, bytes ->
            val rotation = when (index % 4) {
                1 -> DocumentPageRotation.DEGREES_90
                2 -> DocumentPageRotation.DEGREES_180
                3 -> DocumentPageRotation.DEGREES_270
                else -> DocumentPageRotation.DEGREES_0
            }
            val filter = when (index % 4) {
                1 -> DocumentFilter.GRAYSCALE
                2 -> DocumentFilter.BLACK_AND_WHITE
                3 -> DocumentFilter.AUTO_ENHANCE
                else -> DocumentFilter.ORIGINAL
            }
            LibraryPageSource(
                persistentId = "$documentId-page-${index.toString().padStart(2, '0')}",
                contentType = "image/png",
                sourceCategory = DocumentSourceCategory.RENDERED_PDF_PAGE.name,
                imageMetadata = DocumentImageMetadata(
                    sourceByteCount = bytes.size.toLong(),
                    width = PAGE_WIDTH,
                    height = PAGE_HEIGHT,
                ),
                rotation = rotation,
                filter = filter,
                ocrText = when {
                    documentId == LONG_DOCUMENT_ID && index == 0 -> OCR_SENTINEL
                    documentId == LONG_DOCUMENT_ID && index == pageCount - 1 -> "Final archived page"
                    documentId == SHORT_DOCUMENT_ID && index == 1 -> "Receipt total 42.00"
                    else -> null
                },
                ocrError = null,
                openStream = { ByteArrayInputStream(bytes) },
            )
        }
        val sourceAssets = sourcePdf?.let { pdf ->
            listOf(
                LibrarySourceAssetSource(
                    role = "ORIGINAL_DOCUMENT",
                    contentType = "application/pdf",
                    sourceModifiedAtMillis = ORIGINAL_SOURCE_MODIFIED_AT,
                    matchesCurrentRevision = false,
                    openStream = { FileInputStream(pdf) },
                ),
            )
        }.orEmpty()
        val prepared = when (
            val result = rig.fileStore.prepareRevision(documentId, pageSources, sourceAssets)
        ) {
            is LibraryResult.Success -> result.value
            is LibraryResult.Failure -> error("Unable to seed $title: ${result.reason}")
        }
        val pages = prepared.pages.map { page ->
            LibraryPageEntity(
                pageId = page.pageId,
                documentId = documentId,
                position = page.position,
                relativePath = page.relativePath,
                contentType = page.contentType,
                sourceCategory = page.sourceCategory,
                width = page.imageMetadata.width,
                height = page.imageMetadata.height,
                sourceByteCount = page.imageMetadata.sourceByteCount,
                rotationDegrees = page.rotation.degrees,
                filterName = page.filter.name,
                ocrText = page.ocrText,
                ocrError = page.ocrError,
                contentSha256 = page.contentSha256,
            )
        }
        val fingerprint = DocumentFingerprintV1.calculate(
            pages.map { page ->
                FingerprintPage(
                    assetSha256 = requireNotNull(page.contentSha256),
                    mimeType = page.contentType,
                    byteLength = requireNotNull(page.sourceByteCount),
                    rotationDegrees = page.rotationDegrees,
                    filterName = page.filterName,
                )
            },
        )
        val storedSources = prepared.sourceAssets.map { source ->
            LibrarySourceAssetEntity(
                assetId = source.assetId,
                documentId = documentId,
                role = source.role,
                relativePath = source.relativePath,
                contentType = source.contentType,
                byteCount = source.byteCount,
                sha256 = source.sha256,
                sourceModifiedAtMillis = source.sourceModifiedAtMillis,
                createdAtMillis = createdAt,
                matchesCurrentRevision = source.matchesCurrentRevision,
            )
        }
        rig.dao.replaceDocument(
            document = LibraryDocumentEntity(
                documentId = documentId,
                title = title,
                createdAtMillis = createdAt,
                modifiedAtMillis = modifiedAt,
                pageCount = pages.size,
                folderId = folderId,
                thumbnailRelativePath = prepared.thumbnailRelativePath,
                ocrStatus = LibraryOcrStatus.INDEXED.name,
                contentHashVersion = fingerprint.version,
                contentSha256 = fingerprint.sha256,
                contentByteCount = pages.sumOf { requireNotNull(it.sourceByteCount) },
                sourceModifiedAtMillis = storedSources.firstOrNull()?.sourceModifiedAtMillis,
                importedAtMillis = createdAt + 1,
                contentRevision = if (documentId == LONG_DOCUMENT_ID) 7 else 2,
                ocrScriptPreference = if (documentId == LONG_DOCUMENT_ID) "LATIN" else "AUTOMATIC",
                documentDateEpochDay = if (documentId == LONG_DOCUMENT_ID) 20_123 else null,
                documentDateProvenance = if (documentId == LONG_DOCUMENT_ID) {
                    LibraryDocumentDateProvenance.USER_CONFIRMED.name
                } else {
                    null
                },
                documentDateConfidence = if (documentId == LONG_DOCUMENT_ID) {
                    LibraryDocumentDateConfidence.EXACT.name
                } else {
                    null
                },
                originalCreatedAtMillis = if (documentId == LONG_DOCUMENT_ID) createdAt - 500 else null,
                originalCreatedAtProvenance = if (documentId == LONG_DOCUMENT_ID) {
                    LibraryFileTimeProvenance.FILE_METADATA.name
                } else {
                    null
                },
                sourceModifiedAtProvenance = storedSources.firstOrNull()?.let {
                    LibraryFileTimeProvenance.FILE_METADATA.name
                },
                sourceProvenanceKind = if (sourcePdf != null) {
                    LibrarySourceProvenanceKind.GENERIC_PDF.name
                } else {
                    LibrarySourceProvenanceKind.GENERIC_IMAGE.name
                },
                sourceProvenanceLabel = if (sourcePdf != null) "Imported PDF" else "Imported images",
            ),
            pages = pages,
            ocrText = pages.joinToString("\n\n") { it.ocrText.orEmpty() },
            sourceAssets = storedSources,
        )
    }

    private suspend fun logicalSnapshot(rig: LibraryRig): LogicalLibrary {
        val folders = rig.dao.foldersPage(0, 100)
        val foldersById = folders.associateBy { it.folderId }
        fun folderPath(folderId: String?): String? {
            if (folderId == null) return null
            val segments = ArrayDeque<String>()
            var current = foldersById[folderId]
            while (current != null) {
                segments.addFirst(current.name)
                current = current.parentFolderId?.let(foldersById::get)
            }
            return segments.joinToString("/")
        }
        val logicalFolders = folders.map { folder ->
            LogicalFolder(
                path = requireNotNull(folderPath(folder.folderId)),
                createdAt = folder.createdAtMillis,
                modifiedAt = folder.modifiedAtMillis,
            )
        }.sortedBy(LogicalFolder::path)
        val documents = rig.dao.activeDocumentsPage(-1L, 100).map { document ->
            val pages = rig.dao.pages(document.documentId).map { page ->
                val artifacts = rig.dao.ocrArtifacts(page.pageId)
                LogicalPage(
                    position = page.position,
                    mimeType = page.contentType,
                    sha256 = requireNotNull(page.contentSha256),
                    byteLength = requireNotNull(page.sourceByteCount),
                    width = requireNotNull(page.width),
                    height = requireNotNull(page.height),
                    rotation = page.rotationDegrees,
                    filter = page.filterName,
                    brightness = page.brightness, contrast = page.contrast,
                    annotations = page.annotations,
                    paperSize = page.paperSize,
                    ocrText = page.ocrText,
                    ocrError = page.ocrError,
                    visualRevision = page.visualRevision,
                    ocrStateRevision = page.ocrStateRevision,
                    activeArtifactRevision = page.activeOcrArtifactRevision,
                    artifacts = artifacts.map { it.copy(pageId = NORMALIZED_PAGE_ID) },
                    lines = artifacts.flatMap { artifact ->
                        rig.dao.ocrLines(page.pageId, artifact.artifactRevision)
                    }.map { it.copy(pageId = NORMALIZED_PAGE_ID) },
                    correction = rig.dao.ocrCorrection(page.pageId)?.copy(pageId = NORMALIZED_PAGE_ID),
                    correctionLines = rig.dao.ocrCorrectionLines(page.pageId)
                        .map { it.copy(pageId = NORMALIZED_PAGE_ID) },
                    effective = rig.dao.effectiveOcrPage(document.documentId, page.pageId)?.copy(
                        documentId = NORMALIZED_DOCUMENT_ID,
                        pageId = NORMALIZED_PAGE_ID,
                    ),
                )
            }
            assertEquals(pages.indices.toList(), pages.map(LogicalPage::position))
            val fingerprint = DocumentFingerprintV1.calculate(
                pages.map { page ->
                    FingerprintPage(
                        assetSha256 = page.sha256,
                        mimeType = page.mimeType,
                        byteLength = page.byteLength,
                        rotationDegrees = page.rotation,
                        filterName = page.filter,
                        brightness = page.brightness, contrast = page.contrast,
                        annotations = page.annotations,
                    paperSize = page.paperSize,
                    )
                },
            )
            assertEquals(document.contentHashVersion, fingerprint.version)
            assertEquals(document.contentSha256, fingerprint.sha256)
            val sources = rig.dao.sourceAssets(document.documentId).map { source ->
                val file = rig.fileStore.resolve(source.relativePath)
                assertTrue(file?.isFile == true && file.length() == source.byteCount)
                LogicalSource(
                    role = source.role,
                    mimeType = source.contentType,
                    sha256 = source.sha256,
                    byteLength = source.byteCount,
                    modifiedAt = source.sourceModifiedAtMillis,
                    matchesCurrentRevision = source.matchesCurrentRevision,
                )
            }.sortedBy(LogicalSource::sha256)
            LogicalDocument(
                title = document.title,
                folderPath = folderPath(document.folderId),
                createdAt = document.createdAtMillis,
                modifiedAt = document.modifiedAtMillis,
                contentHashVersion = requireNotNull(document.contentHashVersion),
                contentSha256 = requireNotNull(document.contentSha256),
                contentRevision = document.contentRevision,
                scriptPreference = document.ocrScriptPreference,
                documentDateEpochDay = document.documentDateEpochDay,
                documentDateProvenance = document.documentDateProvenance,
                documentDateConfidence = document.documentDateConfidence,
                originalCreatedAt = document.originalCreatedAtMillis,
                originalCreatedAtProvenance = document.originalCreatedAtProvenance,
                sourceModifiedAt = document.sourceModifiedAtMillis,
                sourceModifiedAtProvenance = document.sourceModifiedAtProvenance,
                importedAt = document.importedAtMillis,
                sourceProvenanceKind = document.sourceProvenanceKind,
                sourceProvenanceLabel = document.sourceProvenanceLabel,
                pages = pages,
                sources = sources,
            )
        }.sortedBy(LogicalDocument::title)
        return LogicalLibrary(logicalFolders, documents)
    }

    private fun pngPage(index: Int, longDocument: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(
                Color.rgb(
                    (31 + index * 17) % 255,
                    (83 + index * 29) % 255,
                    (149 + index * 37) % 255,
                ),
            )
            canvas.drawText(
                "${if (longDocument) "Archive" else "Receipt"} ${index + 1}",
                6f,
                28f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 13f
                },
            )
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writePdf(destination: File, pageCount: Int) {
        destination.parentFile?.mkdirs()
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(144, 192, index + 1).create(),
                )
                page.canvas.drawText(
                    "Original source page ${index + 1}",
                    12f,
                    28f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 12f
                    },
                )
                document.finishPage(page)
            }
            FileOutputStream(destination).use(document::writeTo)
        } finally {
            document.close()
        }
        assertTrue(destination.isFile && destination.length() > 0L)
    }

    private fun pdfPageCount(file: File): Int {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
    }

    private data class LibraryRig(
        val database: LibraryDatabase,
        val dao: LibraryDao,
        val fileStore: LibraryFileStore,
        val databaseName: String,
        val context: Context,
    ) {
        fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            fileStore.root.deleteRecursively()
        }

        companion object {
            fun create(context: Context, root: File, databaseName: String): LibraryRig {
                root.deleteRecursively()
                val database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName)
                    .build()
                return LibraryRig(
                    database = database,
                    dao = database.libraryDao(),
                    fileStore = LibraryFileStore(context, root),
                    databaseName = databaseName,
                    context = context,
                )
            }
        }
    }

    private class FileBackupDestination(private val file: File) : BackupPublicationDestination {
        override fun openOutput(): OutputStream? {
            file.parentFile?.mkdirs()
            return FileOutputStream(file, false)
        }

        override fun openInput(): InputStream? = file.takeIf(File::isFile)?.let { FileInputStream(it) }

        override fun delete(): Boolean = !file.exists() || file.delete()
    }

    private class FileRestoreSafAccess(
        private val expected: RestoreSafReference,
        private val file: File,
    ) : RestoreSafAccess {
        override fun open(reference: RestoreSafReference): InputStream {
            require(reference == expected)
            return FileInputStream(file)
        }
    }

    private class CancelAfterSniffRestoreSafAccess(
        private val expected: RestoreSafReference,
        private val file: File,
    ) : RestoreSafAccess {
        private var openCount = 0

        override fun open(reference: RestoreSafReference): InputStream {
            require(reference == expected)
            openCount += 1
            if (openCount == 1) return FileInputStream(file)
            return object : InputStream() {
                override fun read(): Int = throw CancellationException("synthetic restore cancellation")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw CancellationException("synthetic restore cancellation")
            }
        }
    }

    private class SequentialRestoreIdSource(start: Int) : RestoreIdSource {
        private var next = start

        override fun newId(): String = "00000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
    }

    private data class LogicalLibrary(
        val folders: List<LogicalFolder>,
        val documents: List<LogicalDocument>,
    )

    private data class LogicalFolder(val path: String, val createdAt: Long, val modifiedAt: Long)

    private data class LogicalDocument(
        val title: String,
        val folderPath: String?,
        val createdAt: Long,
        val modifiedAt: Long,
        val contentHashVersion: Int,
        val contentSha256: String,
        val contentRevision: Long,
        val scriptPreference: String?,
        val documentDateEpochDay: Long?,
        val documentDateProvenance: String?,
        val documentDateConfidence: String?,
        val originalCreatedAt: Long?,
        val originalCreatedAtProvenance: String?,
        val sourceModifiedAt: Long?,
        val sourceModifiedAtProvenance: String?,
        val importedAt: Long?,
        val sourceProvenanceKind: String?,
        val sourceProvenanceLabel: String?,
        val pages: List<LogicalPage>,
        val sources: List<LogicalSource>,
    )

    private data class LogicalPage(
        val position: Int,
        val mimeType: String,
        val sha256: String,
        val byteLength: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val filter: String,
        val brightness: Int, val contrast: Int,
        val annotations:String,
        val paperSize:String,
        val ocrText: String?,
        val ocrError: String?,
        val visualRevision: Long,
        val ocrStateRevision: Long,
        val activeArtifactRevision: Long?,
        val artifacts: List<LibraryPageOcrArtifactEntity>,
        val lines: List<LibraryPageOcrLineEntity>,
        val correction: LibraryPageOcrCorrectionEntity?,
        val correctionLines: List<LibraryPageOcrCorrectionLineEntity>,
        val effective: LibraryEffectiveOcrPage?,
    )

    private data class LogicalSource(
        val role: String,
        val mimeType: String,
        val sha256: String,
        val byteLength: Long,
        val modifiedAt: Long?,
        val matchesCurrentRevision: Boolean,
    )

    private companion object {
        const val BACKUP_ID = "00000000-0000-4000-8000-000000000901"
        const val BACKUP_CREATED_AT = 1_790_000_000_000L
        const val RESTORED_AT = 1_790_000_100_000L
        const val ROOT_FOLDER_ID = "root-folder"
        const val CHILD_FOLDER_ID = "child-folder"
        const val ROOT_CREATED_AT = 1_700_000_000_000L
        const val ROOT_MODIFIED_AT = 1_700_000_100_000L
        const val CHILD_CREATED_AT = 1_710_000_000_000L
        const val CHILD_MODIFIED_AT = 1_710_000_100_000L
        const val LONG_DOCUMENT_ID = "long-document"
        const val LONG_DOCUMENT_TITLE = "Long archive"
        const val LONG_CREATED_AT = 1_720_000_000_000L
        const val LONG_MODIFIED_AT = 1_720_000_100_000L
        const val LONG_PAGE_COUNT = 25
        const val SHORT_DOCUMENT_ID = "short-document"
        const val SHORT_DOCUMENT_TITLE = "Receipt bundle"
        const val SHORT_CREATED_AT = 1_730_000_000_000L
        const val SHORT_MODIFIED_AT = 1_730_000_100_000L
        const val SHORT_PAGE_COUNT = 3
        const val ORIGINAL_SOURCE_MODIFIED_AT = 1_719_999_900_000L
        const val OCR_SENTINEL = "private quartz archive sentinel"
        const val EMBEDDED_CORRECTION_SENTINEL = "corrected obsidian imported text"
        const val NORMALIZED_DOCUMENT_ID = "document"
        const val NORMALIZED_PAGE_ID = "page"
        const val PAGE_WIDTH = 96
        const val PAGE_HEIGHT = 128
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    return indices.any { start ->
        start + candidate.size <= size && candidate.indices.all { offset ->
            this[start + offset] == candidate[offset]
        }
    }
}
