package org.synapseworks.pageharbor.document.annotation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.synapseworks.pageharbor.document.session.DocumentPageRotation

@RunWith(AndroidJUnit4::class)
class AnnotationRendererInstrumentedTest {
 @Test fun transparentOverlapAndRotatedNonSquarePlacementUseFinalAppearance() {
  val a=AnnotationMark.create("a",AnnotationTool.HIGHLIGHT,AnnotationBounds(.1f,.2f,.6f,.7f),AnnotationStyle(0xFFFF00,.5f))
  val b=AnnotationMark.create("b",AnnotationTool.HIGHLIGHT,AnnotationBounds(.3f,.4f,.8f,.9f),AnnotationStyle(0x0000FF,.5f))
  for(rotation in DocumentPageRotation.entries){
   val swapped=rotation.degrees%180!=0
   val bitmap=Bitmap.createBitmap(if(swapped)200 else 400,if(swapped)400 else 200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
   AnnotationRenderer.draw(bitmap,AnnotationLayer.of(listOf(a,b)),rotation)
   fun sample(x:Float,y:Float)=AnnotationPoint(x,y).rotated(rotation.degrees).let{bitmap.getPixel((it.x*bitmap.width).toInt(),(it.y*bitmap.height).toInt())}
   val overlap=sample(.4f,.5f)
   assertEquals(127f,Color.red(overlap).toFloat(),2f);assertEquals(127f,Color.green(overlap).toFloat(),2f);assertEquals(191f,Color.blue(overlap).toFloat(),2f)
   assertEquals(Color.WHITE,sample(.95f,.05f));bitmap.recycle()
  }
  // Transparent source pixels must use the same white page base in preview and JPEG/PDF output.
  val transparent=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
  transparent.eraseColor(Color.TRANSPARENT)
  try {
   AnnotationRenderer.draw(transparent,AnnotationLayer.of(listOf(a)),DocumentPageRotation.DEGREES_0)
   assertEquals(Color.WHITE,transparent.getPixel(380,10))
   val highlighted=transparent.getPixel(80,60)
   assertEquals(255,Color.alpha(highlighted));assertEquals(127f,Color.blue(highlighted).toFloat(),2f)
  }finally{transparent.recycle()}
 }
 @Test fun visibleAccentedMultilineTextHasGlyphPixelsAndOverflowFailsBeforePainting() {
  val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
  val mark=AnnotationMark.create("text",AnnotationTool.TEXT,AnnotationBounds(.05f,.05f,.95f,.8f),AnnotationStyle(0,1f,.005f,.05f),text="Café Résumé\nÅngström naïve")
  assertNull(AnnotationRenderer.validate(AnnotationLayer.of(listOf(mark)),800,600))
  AnnotationRenderer.draw(bitmap,AnnotationLayer.of(listOf(mark)),DocumentPageRotation.DEGREES_0)
  val pixels=IntArray(800*600);bitmap.getPixels(pixels,0,800,0,0,800,600)
  assertTrue(pixels.take(800*80).count{it!=Color.WHITE}>100)
  assertTrue(pixels.drop(800*80).take(800*80).count{it!=Color.WHITE}>100)
  val overflow=mark.withBounds(AnnotationBounds(.1f,.1f,.2f,.12f))
  assertNotNull(AnnotationRenderer.validate(AnnotationLayer.of(listOf(overflow)),800,600))
  bitmap.eraseColor(Color.WHITE)
  try{AnnotationRenderer.draw(bitmap,AnnotationLayer.of(listOf(overflow)),DocumentPageRotation.DEGREES_0);fail("Overflow accepted")}catch(_:IllegalArgumentException){}
  assertEquals(Color.WHITE,bitmap.getPixel(100,65));bitmap.recycle()
 }
}
