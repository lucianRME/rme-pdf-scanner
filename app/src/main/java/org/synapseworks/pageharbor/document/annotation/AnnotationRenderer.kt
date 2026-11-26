package org.synapseworks.pageharbor.document.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.synapseworks.pageharbor.document.session.DocumentPageRotation
import kotlin.math.max
import kotlin.math.min

/** Local visible Latin text and vector appearance. No PDF annotation objects or hidden text. */
object AnnotationRenderer {
 private const val VIRTUAL_WIDTH=10000f
 private fun layout(mark:AnnotationMark):StaticLayout {
  val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",Typeface.NORMAL);textSize=mark.style.textSize*VIRTUAL_WIDTH;color=0xFF000000.toInt()or mark.style.rgb;alpha=(mark.style.opacity*255).toInt()}
  require(mark.text.filter{it!='\n'}.all{paint.hasGlyph(it.toString())}){"Unsupported visible character"}
  // StaticLayout.Builder documents SIMPLE as its default on every supported API.
  return StaticLayout.Builder.obtain(mark.text,0,mark.text.length,paint,max(1,(mark.bounds.width*VIRTUAL_WIDTH).toInt()))
   .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
 }
 fun validate(layer:AnnotationLayer,sourceWidth:Int,sourceHeight:Int):String? {
  if(sourceWidth<=0||sourceHeight<=0)return "Page dimensions unavailable"
  val virtualHeight=VIRTUAL_WIDTH*sourceHeight.toFloat()/sourceWidth
  return try {
   for(mark in layer.marks)if(mark.tool==AnnotationTool.TEXT){val text=layout(mark)
    if(text.height>mark.bounds.height*virtualHeight+.01f)return "Text does not fit. Enlarge the box or reduce text size."
    for(line in 0 until text.lineCount)if(text.getLineWidth(line)>mark.bounds.width*VIRTUAL_WIDTH+.01f)return "Text does not fit. Enlarge the box or reduce text size."
   };null
  }catch(_:IllegalArgumentException){"Unsupported visible character"}
 }
 /** Caller owns a mutable, already filtered bitmap. Validate all text before drawing any mark. */
 fun draw(bitmap:Bitmap,layer:AnnotationLayer,rotation:DocumentPageRotation,ensureActive:()->Unit={}) {
  if(layer.isEmpty)return
  ensureActive()
  require(bitmap.isMutable)
  val swapped=rotation.degrees%180!=0
  val w=if(swapped)bitmap.height else bitmap.width;val h=if(swapped)bitmap.width else bitmap.height
  require(validate(layer,w,h)==null){"Annotation appearance is invalid"}
  val canvas=Canvas(bitmap)
  // A shared opaque page base prevents source alpha from changing highlight colours at JPEG encode.
  canvas.drawColor(android.graphics.Color.WHITE,android.graphics.PorterDuff.Mode.DST_OVER)
  val saved=canvas.save()
  try {
   when(rotation){DocumentPageRotation.DEGREES_90->{canvas.translate(bitmap.width.toFloat(),0f);canvas.rotate(90f)}
    DocumentPageRotation.DEGREES_180->{canvas.translate(bitmap.width.toFloat(),bitmap.height.toFloat());canvas.rotate(180f)}
    DocumentPageRotation.DEGREES_270->{canvas.translate(0f,bitmap.height.toFloat());canvas.rotate(270f)}
    else->Unit}
   canvas.clipRect(0f,0f,w.toFloat(),h.toFloat())
   for(mark in layer.marks){ensureActive();val b=mark.bounds;val r=RectF(b.left*w,b.top*h,b.right*w,b.bottom*h)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xFF000000.toInt()or mark.style.rgb;alpha=(255*mark.style.opacity).toInt();style=Paint.Style.STROKE;strokeWidth=mark.style.stroke*min(w,h);strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    when(mark.tool){
     AnnotationTool.HIGHLIGHT->{paint.style=Paint.Style.FILL;canvas.drawRect(r,paint)}
     AnnotationTool.RECTANGLE->canvas.drawRect(r,paint)
     AnnotationTool.ELLIPSE->canvas.drawOval(r,paint)
     AnnotationTool.LINE,AnnotationTool.ARROW->{
      val start=mark.points.firstOrNull()?:AnnotationPoint(0f,0f);val end=mark.points.lastOrNull()?:AnnotationPoint(1f,1f)
      val x0=r.left+start.x*r.width();val y0=r.top+start.y*r.height();val x1=r.left+end.x*r.width();val y1=r.top+end.y*r.height()
      canvas.drawLine(x0,y0,x1,y1,paint)
      if(mark.tool==AnnotationTool.ARROW){val dx=x1-x0;val dy=y1-y0;val length=kotlin.math.sqrt(dx*dx+dy*dy)
       if(length>0f){val tip=min(length*.3f,max(paint.strokeWidth*4f,min(w,h)*.025f));val ux=dx/length;val uy=dy/length
        canvas.drawLine(x1,y1,x1-tip*ux+tip*.5f*uy,y1-tip*uy-tip*.5f*ux,paint)
        canvas.drawLine(x1,y1,x1-tip*ux-tip*.5f*uy,y1-tip*uy+tip*.5f*ux,paint)}
      }
     }
     AnnotationTool.SIGNATURE->{
      val ink=requireNotNull(mark.signature)
      paint.strokeWidth=ink.strokeWidthFraction*min(r.width(),r.height())*(mark.style.stroke/.005f)
      for(stroke in ink.strokes){
       ensureActive()
       if(stroke.points.size==1){val p=stroke.points.single();paint.style=Paint.Style.FILL;canvas.drawCircle(r.left+p.x*r.width(),r.top+p.y*r.height(),paint.strokeWidth/2f,paint);paint.style=Paint.Style.STROKE}
       else{val path=Path();stroke.points.forEachIndexed{i,p->ensureActive();val x=r.left+p.x*r.width();val y=r.top+p.y*r.height();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};canvas.drawPath(path,paint)}
      }
     }
     AnnotationTool.FREEHAND->{val path=Path();mark.points.forEachIndexed{i,p->ensureActive();val x=r.left+p.x*r.width();val y=r.top+p.y*r.height();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};canvas.drawPath(path,paint)}
     AnnotationTool.TEXT->{val restore=canvas.save();try{canvas.translate(r.left,r.top);canvas.scale(w/VIRTUAL_WIDTH,w/VIRTUAL_WIDTH);layout(mark).draw(canvas)}finally{canvas.restoreToCount(restore)}}
    }
   }
  }finally{canvas.restoreToCount(saved)}
 }
}
