package org.synapseworks.pageharbor.validation

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.synapseworks.pageharbor.ocr.MlKitOcrEngine

/** Existing bundled OCR after the newly shared native runtime has loaded. */
class ModelNativeChecks:Instrumentation(){
 override fun onCreate(args:Bundle?){super.onCreate(args);start()}
 override fun onStart(){val out=Bundle();try{
  check(targetContext.packageName.endsWith(".m36packsmoke") || targetContext.packageName.endsWith(".m36packr8"))
  System.loadLibrary("djl_tokenizer");System.loadLibrary("onnxruntime")
  val image=Bitmap.createBitmap(1000,400,Bitmap.Config.ARGB_8888)
  try{val canvas=Canvas(image);canvas.drawColor(Color.WHITE);canvas.drawText("Harbor",70f,220f,Paint().apply{color=Color.BLACK;textSize=100f;isAntiAlias=true});val bytes=java.io.ByteArrayOutputStream().also{check(image.compress(Bitmap.CompressFormat.JPEG,100,it))}.toByteArray();val r=MlKitOcrEngine().recognize(listOf(org.synapseworks.pageharbor.ocr.OcrPage{java.io.ByteArrayInputStream(bytes)}));check(r.pages.single().error==null && r.pages.single().text.contains("Harbor",ignoreCase=true))}finally{image.recycle()}
  out.putString("status","PASS");out.putBoolean("existingOcrAfterNativeLoad",true)
 }catch(t:Throwable){out.putString("status","FAIL");out.putString("failureClass",t.javaClass.simpleName)};finish(Activity.RESULT_OK,out)}
}
