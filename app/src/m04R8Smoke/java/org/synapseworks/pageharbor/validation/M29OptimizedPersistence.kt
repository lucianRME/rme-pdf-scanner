package org.synapseworks.pageharbor.validation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import java.io.*
import java.util.UUID
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.backup.engine.*
import org.synapseworks.pageharbor.backup.format.BackupProducer
import org.synapseworks.pageharbor.backup.publish.*
import org.synapseworks.pageharbor.backup.restore.*
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.objectremoval.*
import org.synapseworks.pageharbor.document.finger.*
import org.synapseworks.pageharbor.document.annotation.*
import org.synapseworks.pageharbor.document.signature.*
import org.synapseworks.pageharbor.portability.export.*
import org.synapseworks.pageharbor.ui.home.applyPreviewRotationAndFilter

/** Synthetic, isolated optimized validation only; no production storage or destination is used. */
internal suspend fun fingerPersistence(context:Context,original:DocumentSession,acceptedBytes:ByteArray,evidence:File) {
 val token=UUID.randomUUID().toString();val root=File(context.filesDir,"document-library/m29-$token");val scratch=File(context.cacheDir,"shared-pdfs/m29-persistence-$token").apply{mkdirs()}
 class Rig(val label:String){val name="m29-$label-$token.db";var database=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build();val dao get()=database.libraryDao();val files=LibraryFileStore(context,File(root,label));fun reopen(){database.close();database=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()};fun close(){database.close();context.deleteDatabase(name);files.root.deleteRecursively()}}
 val source=Rig("source");val plain=Rig("plain");val encrypted=Rig("encrypted");val processor=ObjectRemovalProcessor(context);val work=processor.begin();val password="synthetic-m29-backup".toCharArray()
 try {
  fingerRemovalSmokeStage="layered-fixture"
  val ink=HandwrittenSignature(listOf(SignatureStroke(listOf(SignaturePoint(.5f,.5f)))),strokeWidthFraction=.04f)
  val layer=AnnotationLayer.of(listOf(
   AnnotationMark.create("m29-signature",AnnotationTool.SIGNATURE,AnnotationBounds(.55f,.24f,.65f,.34f),AnnotationStyle(rgb=0),signature=ink),
   AnnotationMark.create("m29-highlight",AnnotationTool.HIGHLIGHT,AnnotationBounds(.2f,.42f,.45f,.49f),AnnotationStyle(rgb=0xFFFF00,opacity=.4f))))
  val marked=original.copy(pages=original.pages.map{it.copy(annotations=layer)})
  val page=marked.pages.single();val analysis=processor.open(work,page);val detected=processor.detect(work,page,analysis);check(detected.proposals==1)
  val prepared=requireNotNull(processor.preview(work,page,analysis,detected.mask,true).prepared);processor.verify(work,analysis,prepared)
  val after=requireNotNull(removalPublication(marked,prepared)).after;check(after.pages.single().annotations==layer)
  fun bytes(page:DocumentPage)=context.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{it.readBytes()}
  check(bytes(after.pages.single()).contentEquals(acceptedBytes))
  fingerRemovalSmokeStage="library-save-reopen"
  val saved=(LibraryRepository(context,source.dao,source.files).saveSession(after,"Synthetic M29 optimized")as LibraryResult.Success).value
  source.reopen();check(source.dao.activeDocumentsPage(-1,10).single().documentId==saved.id)
  suspend fun verify(rig:Rig):DocumentSession {
   val record=rig.dao.activeDocumentsPage(-1,10).single();val reopened=(LibraryRepository(context,rig.dao,rig.files).openDocument(record.documentId)as LibraryResult.Success).value.session;val actual=reopened.pages.single();val expected=after.pages.single()
   check(bytes(actual).contentEquals(acceptedBytes));check(actual.filter==expected.filter&&actual.adjustments==expected.adjustments&&actual.rotation==expected.rotation&&actual.annotations==layer)
   check(rig.dao.pages(record.documentId).single().ocrText==null);val thumb=requireNotNull(rig.files.resolve(requireNotNull(record.thumbnailRelativePath)));check(thumb.length()>0);val bitmap=BitmapFactory.decodeFile(thumb.path);check(bitmap!=null);bitmap.recycle()
   return reopened
  }
  verify(source)
  fingerRemovalSmokeStage="backup-create"
  val backup=(LibraryBackupEngine(RoomLibraryBackupSnapshotSource(source.dao,source.files),FileLibraryBackupWorkspace(File(scratch,"create")),LibraryOperationGate(),LibraryBackupStoragePreflight{true}).create(BackupProducer(context.packageName,"synthetic",1))as LibraryBackupCreationResult.Verified).artifact
  class Destination(val file:File):BackupPublicationDestination{override fun openOutput():OutputStream{file.parentFile!!.mkdirs();return file.outputStream()};override fun openInput():InputStream=file.inputStream();override fun delete()=!file.exists()||file.delete()}
  val zip=File(scratch,"published/plain.zip");val secret=File(scratch,"published/encrypted.rmebackup")
  try {
   check(backup.manifest.summary.documentCount==1&&backup.manifest.summary.pageCount==1)
   check(BackupPublicationEngine().publishUnencrypted(backup,Destination(zip))is BackupPublicationResult.Verified)
   check(BackupPublicationEngine().publishEncrypted(backup,Destination(secret),password)is BackupPublicationResult.Verified)
   for((rig,file,key)in listOf(Triple(plain,zip,null),Triple(encrypted,secret,password))){
    fingerRemovalSmokeStage="restore-"+rig.label;val stage=File(scratch,"restore-${rig.label}");val reference=RestoreSafReference("synthetic-m29")
    val coordinator=AndroidRestoreCoordinator(engine=LibraryRestoreEngine(store=RoomRestoreLibraryStore(context,rig.dao,rig.files),stagingWorkspace=FileRestoreStagingWorkspace(File(stage,"verified")),operationGate=LibraryOperationGate(),storagePreflight=RestoreStoragePreflight{true}),safAccess=object:RestoreSafAccess{override fun open(reference:RestoreSafReference)=file.inputStream()},archiveWorkspace=FileRestoreArchiveWorkspace(File(stage,"archive")),archivePreflight=RestoreStoragePreflight{true},testing=Unit)
    val preview=coroutineScope{async{coordinator.prepare(reference,key)}.await()}as RestoreCoordinatorPrepareResult.Ready
    val restored=coroutineScope{async{coordinator.restore(preview.prepared,RestoreMergePolicy.MERGE_IMPORT_ANYWAY)}.await()}as RestoreResult.Completed
    check(restored.importedDocumentCount==1&&restored.stagingCleanupSucceeded);rig.reopen();verify(rig)
    check(File(stage,"verified").listFiles().isNullOrEmpty()&&File(stage,"archive").listFiles().isNullOrEmpty())
   }
  }finally{backup.close()}
  fingerRemovalSmokeStage="restored-layer-export"
  val reopened=verify(encrypted);val record=encrypted.dao.activeDocumentsPage(-1,10).single();val stream=ByteArrayOutputStream()
  check(RoomPortableExportPdfSource(context,encrypted.dao,encrypted.files).writePdf(record.documentId,stream)==PortableExportPdfWriteResult.WRITTEN)
  val pdf=File(evidence,"optimized-layered.pdf").apply{writeBytes(stream.toByteArray())};val p=reopened.pages.single();val raw=requireNotNull(context.contentResolver.openInputStream(p.source.toAndroidUri())!!.use{BitmapFactory.decodeStream(it)})
  val expected=applyPreviewRotationAndFilter(raw,p.rotation,p.filter,p.adjustments,p.annotations)
  try {
   File(evidence,"optimized-layered-preview.png").outputStream().use{check(expected.compress(Bitmap.CompressFormat.PNG,100,it))}
   ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(0).use{page->val actual=Bitmap.createBitmap(expected.width,expected.height,Bitmap.Config.ARGB_8888);try{page.render(actual,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    for((x,y)in listOf(307 to 111,125 to 170))for(shift in listOf(0,8,16))check(kotlin.math.abs((actual.getPixel(x,y)ushr shift and 255)-(expected.getPixel(x,y)ushr shift and 255))<=16)
    check(android.graphics.Color.red(actual.getPixel(307,111))<80)
   }finally{actual.recycle()}}}}
  }finally{if(expected!==raw)expected.recycle();raw.recycle()}
 }finally{password.fill('\u0000');processor.finish(work);source.close();plain.close();encrypted.close();root.deleteRecursively();scratch.deleteRecursively()}
}
