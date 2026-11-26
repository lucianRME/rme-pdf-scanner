package org.synapseworks.pageharbor.validation
import android.app.*
import android.os.Bundle
import android.net.Uri
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.synapseworks.pageharbor.translation.*
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.ocr.*

/** Public synthetic fixtures only; independent extraction/rendering happens on the host. */
class TranslationPdfChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val result=Bundle();val root=File(targetContext.cacheDir,"m37-pdf-check-"+UUID.randomUUID());var vm:TranslationViewModel?=null
 try{check(targetContext.packageName.endsWith(".m36packsmoke")||targetContext.packageName.endsWith(".m36packr8"));check(root.mkdir());val source=File(root,"source.bin").apply{writeBytes(byteArrayOf(1,2,3))};val expectedSource="Hello world.\n\nAna has 12 books.\n\nRomania and Bucharest."
  fun waitReady(){repeat(650){if(vm!!.busy)Thread.sleep(200)};check(!vm!!.busy)}
  runOnMainSync{vm=TranslationViewModel(targetContext.applicationContext as Application);vm!!.open(DocumentSession(listOf(DocumentPage(DocumentPageId(88),DocumentResource(Uri.fromFile(source).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,persistentId="synthetic-1"))),31,{true},{}){OcrResult(listOf(OcrPageResult(0,expectedSource)))}};waitReady();runOnMainSync{vm!!.translate()};waitReady();check(vm!!.automatic.contains("Bună"));val raw=vm!!.automatic
  runOnMainSync{vm!!.beginCorrection();check(vm!!.correctionSelected.isEmpty());vm!!.cancelCorrection();check(vm!!.draft==raw);vm!!.beginCorrection();vm!!.selectAllCorrections();vm!!.applyCorrection();check(vm!!.draft!=raw&&vm!!.draft.contains('ț')&&vm!!.automatic==raw);vm!!.undoCorrection();check(vm!!.draft==raw&&vm!!.automatic==raw)}
  result.putString("stage","reviewed PDF")
  val fixture=JSONObject(targetContext.assets.open("m37-pdf-fixtures.json").bufferedReader().use{it.readText()});val reviewed=fixture.getString("reviewedText");runOnMainSync{vm!!.edit(fixture.getString("removedCanary")+"\n"+reviewed);vm!!.edit(reviewed);vm!!.reviewed=true;vm!!.preparePdf()};waitReady();check(vm!!.pdfReady&&vm!!.pdfPages>1&&vm!!.automatic==raw&&vm!!.pages.single().text==expectedSource)
  val target=File(root,"reviewed.pdf").apply{createNewFile()};val gate=TranslationIoGate(target,true,true);gate.release.countDown();TranslationControlProvider.active=gate
  runOnMainSync{check(vm!!.beginPdfSave()=="Translation.pdf");val old=vm!!.saveTicket;vm!!.destination(old,null);check(vm!!.beginPdfSave()=="Translation.pdf");vm!!.destination(old,Uri.parse("content://"+targetContext.packageName+".m37-control/owned-control"));check(vm!!.picker&&target.length()==0L);vm!!.edit("late retarget");check(vm!!.draft==reviewed);vm!!.destination(Uri.parse("content://"+targetContext.packageName+".m37-control/owned-control"))};waitReady();check(target.length()>0&&vm!!.message.startsWith("Separate searchable PDF saved"))
  val evidence=File(targetContext.cacheDir,"m37-pdf-evidence").apply{mkdirs()};target.copyTo(File(evidence,"reviewed.pdf"),overwrite=true);File(evidence,"reviewed.txt").writeBytes(TranslationUtf8.encode(reviewed));result.putInt("pages",vm!!.pdfPages)
  result.putString("stage","legacy-default regression")
  val legacy=File(root,"legacy.jpg");val bitmap=android.graphics.Bitmap.createBitmap(600,120,android.graphics.Bitmap.Config.ARGB_8888);val legacyText="Șș Țț Şş Ţţ μ µ"
  try{val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.BLACK;textSize=26f;typeface=android.graphics.Typeface.MONOSPACE};canvas.drawText(legacyText,10f,50f,paint);legacy.outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it))}}finally{bitmap.recycle()}
  val defaultPdf=File(root,"legacy-default.pdf")
  kotlinx.coroutines.runBlocking{val page=org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfPage({legacy.inputStream()},OcrPageResult(0,legacyText,layout=OcrPageLayout(600,120,lines=listOf(OcrTextLine(legacyText,OcrTextBounds(10f,20f,590f,60f))))));check(org.synapseworks.pageharbor.document.searchablepdf.PdfBoxSearchablePdfGenerator(targetContext).generate(org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfRequest(listOf(page),defaultPdf,root)) is org.synapseworks.pageharbor.document.searchablepdf.SearchablePdfGenerationResult.Success)}
  com.tom_roush.pdfbox.pdmodel.PDDocument.load(defaultPdf).use{doc->check(com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc).contains(legacyText));check(doc.getPage(0).resources.fontNames.all{doc.getPage(0).resources.getFont(it).cosObject.getNameAsString(com.tom_roush.pdfbox.cos.COSName.SUBTYPE)=="Type3"})};defaultPdf.copyTo(File(evidence,"legacy-default.pdf"),overwrite=true);result.putString("legacyDefault","PASS_Type3_Unicode_preserved")
  runOnMainSync{vm!!.edit("Unsupported 😀");vm!!.reviewed=true;vm!!.preparePdf()};waitReady();check(!vm!!.pdfReady&&vm!!.draft=="Unsupported 😀"&&vm!!.automatic==raw)
  result.putString("stage","complete");check(source.readBytes().contentEquals(byteArrayOf(1,2,3)));result.putString("status","PASS");result.putString("scope","preview/cancel/apply/undo; raw/source unchanged; reviewed-only multi-page PDF; frozen format/revision; unsupported glyph declined")
 }catch(t:Throwable){result.putString("status","FAIL");result.putString("failureClass",t.javaClass.simpleName)}finally{TranslationControlProvider.active?.release?.countDown();TranslationControlProvider.active=null;runOnMainSync{vm?.close()};root.deleteRecursively()};finish(Activity.RESULT_OK,result)}
}
