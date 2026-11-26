package org.synapseworks.pageharbor.validation
import android.app.*
import android.os.*
import android.net.Uri
import android.graphics.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.*
import org.synapseworks.pageharbor.document.importing.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.image.DocumentFilter
import org.synapseworks.pageharbor.migration.*
import org.synapseworks.pageharbor.ui.home.*
class ImageImportChecks:Instrumentation(){
 override fun onCreate(a:Bundle?){super.onCreate(a);start()}
 override fun onStart(){val result=Bundle();val root=File(targetContext.cacheDir,"rg006-controls-"+UUID.randomUUID());val evidence=File(targetContext.cacheDir,"rg006-evidence");val sessions=mutableListOf<PageHarborSessionViewModel>();var stage="start"
 fun hash(f:File)=f.inputStream().use{val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=it.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)}}
 try{check(root.mkdir());evidence.mkdirs();val corpus=JSONObject(targetContext.assets.open("rg006-matrix.json").bufferedReader().use{it.readText()});val rows=corpus.getJSONArray("fixtures");val files=mutableMapOf<String,File>();val exports=JSONArray()
 for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val n=row.getString("name");stage=n;val f=File(root,n);targetContext.assets.open("rg006/$n").use{input->f.outputStream().use{input.copyTo(it)}};check(hash(f)==row.getString("sha256"));files[n]=f
  val vm=PageHarborSessionViewModel();sessions+=vm;check(vm.beginImportRequest(DocumentImportOrigin.PICKER));val processor=DocumentImportProcessor(targetContext){vm.registerPendingAcquisitionResources(listOf(it))==PendingResourceRegistrationResult.Registered};val prepared=runBlocking{processor.prepare(listOf(Uri.fromFile(f)),DocumentSourceCategory.SELECTED_IMAGE,10)}
  if(row.getString("expected")!="ACCEPT"){check(prepared==DocumentImportPreparationResult.Failure(DocumentImportError.valueOf(row.getString("expected"))));vm.failImportRequest((prepared as DocumentImportPreparationResult.Failure).reason);continue}
  check(prepared is DocumentImportPreparationResult.Success);vm.completeImportRequest(prepared);val page=vm.documentPages.single();check(page.imageMetadata.width==row.getInt("width")&&page.imageMetadata.height==row.getInt("height"));val captured=targetContext.contentResolver.openInputStream(page.source.toAndroidUri())!!.use{it.readBytes()};check(MessageDigest.getInstance("SHA-256").digest(captured).joinToString(""){"%02x".format(it)}==row.getString("sha256"));check(page.source.ownership==DocumentResourceOwnership.RME_OWNED_TEMPORARY)
  if(n=="exif90.jpg"){
   val platform=f.inputStream().use{android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}
   val library=f.inputStream().use{androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)}
   result.putInt("qualifiedExif",readOrientation{f.inputStream()});result.putInt("platformExif",platform);result.putInt("androidxExif",library);File(evidence,"exif-readers.json").writeText(JSONObject().put("platform",platform).put("androidx",library).put("expected",6).toString(2))
  }
  val output=File(evidence,n+".jpg");val status=writeFilteredJpegToDestination({targetContext.contentResolver.openInputStream(page.source.toAndroidUri())},output.outputStream(),DocumentFilter.ORIGINAL,imageMetadata=page.imageMetadata);check(status==PageExportResult.Success)
  val bitmap=BitmapFactory.decodeFile(output.path)!!;try{val rotated=row.getInt("orientation")==6;check(bitmap.width==row.getInt(if(rotated)"workingHeight"else"workingWidth")&&bitmap.height==row.getInt(if(rotated)"workingWidth"else"workingHeight"));exports.put(JSONObject().put("source",n).put("sourceSha256",hash(f)).put("jpegSha256",hash(output)).put("width",bitmap.width).put("height",bitmap.height))}finally{bitmap.recycle()}
  val request=FilteredPreviewRequest(pageId=page.id.value,sourceKey=page.source.reference,filter=page.filter,rotation=page.rotation,imageMetadata=page.imageMetadata)
  val preview=decodeDocumentPreview(targetContext.contentResolver,page.source.toAndroidUri(),request)?:error("PREVIEW");try{check(preview.width.toLong()*preview.height<=2500000);if(row.getInt("orientation")==6)check(preview.height>preview.width)}finally{preview.recycle()}
  val plan=normalPdfExportPlan(vm.documentSession)as NormalPdfExportPlan.RecomposeFromPages;val pdf=runBlocking{recomposeNormalPdf(targetContext,plan.pages)};check(pdf is NormalPdfRecompositionResult.Ready);pdf.file.copyTo(File(evidence,n+".pdf"),true);deleteNormalPdfRecomposition(pdf.file);check(hash(f)==row.getString("sha256"))
  val mt=MigrationContentType.values().single{it.mimeType==page.contentType};val migrationRoot=File(root,"migration-$i")
  val migrationPlan=MigrationDocumentPlan(id="public-$i",suggestedTitle="Public RG006",relativeFolderPath=emptyList(),sources=listOf(InspectedMigrationSource(source=MigrationSource(id="public-source",displayName=n,sizeBytes=f.length(),declaredContentType=mt.mimeType),contentType=mt)),grouping=MigrationDocumentGrouping.SINGLE_IMAGE)
  val staged=runBlocking{AndroidMigrationDocumentStager(migrationRoot,MigrationSourceAccess{f.inputStream()}).stage(migrationPlan,MigrationPublicationContext(NeverCancelMigration){_,_->})};check(staged is MigrationStagingResult.Ready);staged.document.use{check(it.pages.single().sha256==row.getString("sha256")&&hash(it.pages.single().file)==row.getString("sha256"))};check(migrationRoot.listFiles().orEmpty().isEmpty());result.putBoolean("inboundMigrationStaging",true);vm.clearScan()
 }
 for((name,expected)in listOf("exif-conflict.jpg" to DocumentImportError.INVALID_IMAGE,"edge-over-limit.png" to DocumentImportError.SOURCE_TOO_LARGE)){
  val f=File(root,name);targetContext.assets.open("rg006/$name").use{input->f.outputStream().use{input.copyTo(it)}};val vm=PageHarborSessionViewModel();sessions+=vm;check(vm.beginImportRequest(DocumentImportOrigin.PICKER));val answer=runBlocking{DocumentImportProcessor(targetContext){vm.registerPendingAcquisitionResources(listOf(it))==PendingResourceRegistrationResult.Registered}.prepare(listOf(Uri.fromFile(f)),DocumentSourceCategory.SELECTED_IMAGE,10)};check(answer==DocumentImportPreparationResult.Failure(expected));vm.failImportRequest(expected)
 }
 stage="metadata and limits";val source=files.getValue("phone-jpeg.jpg");val uri=Uri.parse("content://${targetContext.packageName}.rg006-control/owned-input");ImageImportControlProvider.file=source
 fun prepare(uri:Uri,storage:Long?=null,heap:Long?=null):DocumentImportPreparationResult{val vm=PageHarborSessionViewModel();sessions+=vm;check(vm.beginImportRequest(DocumentImportOrigin.PICKER));return runBlocking{DocumentImportProcessor(targetContext,storage?.let{{it}},heap?.let{{it}}){vm.registerPendingAcquisitionResources(listOf(it))==PendingResourceRegistrationResult.Registered}.prepare(listOf(uri),DocumentSourceCategory.SELECTED_IMAGE,10)}}
 for(size in listOf(null,0L,1L,9999999999L)){ImageImportControlProvider.size=size;ImageImportControlProvider.mime="application/pdf";check(prepare(uri)is DocumentImportPreparationResult.Success)}
 val captured=prepare(uri)as DocumentImportPreparationResult.Success;val capturedVm=sessions.last();ImageImportControlProvider.denied=true;capturedVm.completeImportRequest(captured);check(targetContext.contentResolver.openInputStream(capturedVm.documentPages.single().source.toAndroidUri())!!.use{it.readBytes()}.contentEquals(source.readBytes()));ImageImportControlProvider.denied=false
 check(prepare(uri,storage=0)==DocumentImportPreparationResult.Failure(DocumentImportError.INSUFFICIENT_STORAGE));check(prepare(uri,heap=0)==DocumentImportPreparationResult.Failure(DocumentImportError.INSUFFICIENT_MEMORY));ImageImportControlProvider.denied=true;check(prepare(uri)==DocumentImportPreparationResult.Failure(DocumentImportError.UNREADABLE_SOURCE));ImageImportControlProvider.denied=false
 val huge=File(root,"padded.jpg");huge.outputStream().use{o->source.inputStream().use{it.copyTo(o)};val zero=ByteArray(65536);var remaining=67108865-source.length();while(remaining>0){val n=minOf(remaining,zero.size.toLong()).toInt();o.write(zero,0,n);remaining-=n}};ImageImportControlProvider.file=huge;ImageImportControlProvider.size=null;check(prepare(uri)==DocumentImportPreparationResult.Failure(DocumentImportError.SOURCE_TOO_LARGE));ImageImportControlProvider.size=1;check(prepare(uri)==DocumentImportPreparationResult.Failure(DocumentImportError.SOURCE_TOO_LARGE))
 check(copyPageToDestination(huge.inputStream(),object:java.io.OutputStream(){override fun write(v:Int){};override fun write(b:ByteArray,o:Int,n:Int){}},DocumentImageMetadata())==PageExportResult.SourceTooLarge)
 stage="order and lifecycle";val vm=PageHarborSessionViewModel();sessions+=vm
 fun acquire(names:List<String>){check(vm.beginImportRequest(DocumentImportOrigin.PICKER));val p=runBlocking{DocumentImportProcessor(targetContext){vm.registerPendingAcquisitionResources(listOf(it))==PendingResourceRegistrationResult.Registered}.prepare(names.map{Uri.fromFile(files.getValue(it))},DocumentSourceCategory.SELECTED_IMAGE,20)}as DocumentImportPreparationResult.Success;vm.completeImportRequest(p)}
 acquire(listOf("phone-jpeg.jpg","phone-png.png"));check(vm.documentPages.map{it.contentType}==listOf("image/jpeg","image/png"));val ids=vm.documentPages.map{it.id};acquire(listOf("small.jpg"));check(vm.documentPages.size==3&&vm.documentPages.take(2).map{it.id}==ids)
 val prior=vm.documentSession;check(vm.beginImportRequest(DocumentImportOrigin.PICKER));ImageImportControlProvider.file=source;ImageImportControlProvider.opens=0;ImageImportControlProvider.gateOpen=2;ImageImportControlProvider.entered=java.util.concurrent.CountDownLatch(1);ImageImportControlProvider.release=java.util.concurrent.CountDownLatch(1)
 val task=runBlocking{val j=launch(Dispatchers.Default){DocumentImportProcessor(targetContext){vm.registerPendingAcquisitionResources(listOf(it))==PendingResourceRegistrationResult.Registered}.prepare(listOf(uri),DocumentSourceCategory.SELECTED_IMAGE,10)};check(ImageImportControlProvider.entered!!.await(5,java.util.concurrent.TimeUnit.SECONDS));vm.cancelImportRequest();j.cancel();ImageImportControlProvider.release!!.countDown();j.join();j};check(task.isCancelled);vm.cancelImportRequest();check(vm.documentSession==prior)
 check(vm.beginImportRequest(DocumentImportOrigin.PICKER));vm.cancelImportRequest();check(vm.documentSession==prior)
 File(evidence,"exports.json").writeText(exports.toString(2));result.putString("status","PASS");result.putInt("frozenCases",rows.length());result.putString("scope","Exact source snapshots, bounded preview/JPEG/PDF, EXIF, false/null metadata, actual 64MiB stream cap, revoked access, low heap/storage, new/multi/append/order/cancel")
 }catch(t:Throwable){result.putString("status","FAIL");result.putString("failureClass",t.javaClass.name);result.putString("frames",t.stackTrace.take(6).joinToString(";"){it.className+"."+it.methodName+":"+it.lineNumber})}finally{sessions.forEach{it.cancelImportRequest();it.clearScan()};ImageImportControlProvider.release?.countDown();ImageImportControlProvider.gateOpen=0;ImageImportControlProvider.file=null;ImageImportControlProvider.denied=false;root.deleteRecursively()};result.putString("stage",stage);finish(Activity.RESULT_OK,result)}
}
