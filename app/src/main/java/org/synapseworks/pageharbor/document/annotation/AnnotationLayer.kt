package org.synapseworks.pageharbor.document.annotation

import org.synapseworks.pageharbor.document.signature.HandwrittenSignature
import org.synapseworks.pageharbor.document.signature.MAX_SIGNATURE_ENCODING
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Collections
import kotlin.math.min

/** EXIF-upright source-normalized coordinates, before the session quarter-turn. */
data class AnnotationPoint(val x:Float,val y:Float) {
 init { require(x.isFinite()&&y.isFinite()&&x in 0f..1f&&y in 0f..1f) }
 fun rotated(degrees:Int):AnnotationPoint=when(degrees) {
  0->this;90->AnnotationPoint(1f-y,x);180->AnnotationPoint(1f-x,1f-y);270->AnnotationPoint(y,1f-x)
  else->throw IllegalArgumentException("Unsupported rotation")
 }
}
data class AnnotationBounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
 init { require(listOf(left,top,right,bottom).all{it.isFinite()&&it in 0f..1f});require(right-left>=.0099f&&bottom-top>=.0099f) }
 val width get()=right-left
 val height get()=bottom-top
 fun moved(dx:Float,dy:Float):AnnotationBounds {
  require(dx.isFinite()&&dy.isFinite());val x=dx.coerceIn(-left,1f-right);val y=dy.coerceIn(-top,1f-bottom)
  return AnnotationBounds(left+x,top+y,right+x,bottom+y)
 }
 /** Preserve physical aspect at a page edge, including Float rounding at the last pixel. */
 fun proportionallyResized(scale:Float):AnnotationBounds {
  require(scale.isFinite()&&scale>0f)
  val factor=min(scale,min((1f-left)/width,(1f-top)/height))
  if(width*factor<.01f||height*factor<.01f)return this
  return AnnotationBounds(left,top,(left+width*factor).coerceAtMost(1f),(top+height*factor).coerceAtMost(1f))
 }
 fun resized(scale:Float,verticalScale:Float=scale):AnnotationBounds {
  require(scale.isFinite()&&scale>0f&&verticalScale.isFinite()&&verticalScale>0f)
  val w=(width*scale).coerceIn(min(.01f,1f-left),1f-left);val h=(height*verticalScale).coerceIn(min(.01f,1f-top),1f-top)
  return AnnotationBounds(left,top,left+w,top+h)
 }
}
enum class AnnotationTool { FREEHAND,HIGHLIGHT,ARROW,RECTANGLE,ELLIPSE,LINE,TEXT,SIGNATURE }
data class AnnotationStyle(val rgb:Int=0xD02020,val opacity:Float=1f,val stroke:Float=.005f,val textSize:Float=.035f) {
 init { require(rgb in 0..0xFFFFFF);require(opacity.isFinite()&&opacity in .05f..1f);require(stroke.isFinite()&&stroke in .001f .. .05f);require(textSize.isFinite()&&textSize in .01f .. .15f) }
}
class AnnotationMark private constructor(val id:String,val tool:AnnotationTool,val bounds:AnnotationBounds,val style:AnnotationStyle,val points:List<AnnotationPoint>,val text:String,val signature:HandwrittenSignature?) {
 fun withBounds(value:AnnotationBounds)=create(id,tool,value,style,points,text,signature)
 fun withStyle(value:AnnotationStyle)=create(id,tool,bounds,value,points,text,signature)
 fun withText(value:String)=create(id,tool,bounds,style,points,value,signature)
 override fun equals(other:Any?)=other is AnnotationMark&&id==other.id&&tool==other.tool&&bounds==other.bounds&&style==other.style&&points==other.points&&text==other.text&&signature==other.signature
 override fun hashCode()=listOf(id,tool,bounds,style,points,text,signature).hashCode()
 // Deliberately exclude document text/geometry from diagnostic strings.
 override fun toString()="AnnotationMark($tool)"
 companion object {
  fun supportedText(text:String)=text.length<=4096&&text.isNotBlank()&&text.all{it=='\n'||(it in '\u0020'..'\u024f'&&!Character.isISOControl(it)&&Character.getType(it)!=Character.FORMAT.toInt())}
  fun create(id:String,tool:AnnotationTool,bounds:AnnotationBounds,style:AnnotationStyle=AnnotationStyle(),points:List<AnnotationPoint> = emptyList(),text:String="",signature:HandwrittenSignature?=null):AnnotationMark {
   require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
   require(when(tool){AnnotationTool.FREEHAND->points.size in 2..2048;AnnotationTool.ARROW,AnnotationTool.LINE->points.isEmpty()||points.size==2;else->points.isEmpty()})
   require((tool==AnnotationTool.SIGNATURE)==(signature!=null))
   require(if(tool==AnnotationTool.TEXT)supportedText(text) else text.isEmpty())
   return AnnotationMark(id,tool,bounds,style,Collections.unmodifiableList(points.toList()),text,signature)
  }
 }
}
/** Immutable ordered app-editable layer. Empty encoding preserves old unannotated identities. */
class AnnotationLayer private constructor(val marks:List<AnnotationMark>) {
 val isEmpty get()=marks.isEmpty()
 private val encoded by lazy(LazyThreadSafetyMode.PUBLICATION){encodeBytes()}
 val fingerprint:String by lazy(LazyThreadSafetyMode.PUBLICATION){java.security.MessageDigest.getInstance("SHA-256").digest(encode().toByteArray(Charsets.US_ASCII)).joinToString(""){"%02x".format(it)}}
 val metadataBytes:Long by lazy(LazyThreadSafetyMode.PUBLICATION){512L+2L*encode().length+marks.sumOf{384L+it.id.length*2L+it.text.length*2L+it.points.size*40L+(it.signature?.metadataBytes?:0L)}}
 fun put(mark:AnnotationMark):AnnotationLayer=of(if(marks.any{it.id==mark.id})marks.map{if(it.id==mark.id)mark else it}else marks+mark)
 fun remove(id:String)=of(marks.filterNot{it.id==id})
 fun encode():String=encoded
 private fun encodeBytes():String {
  if(isEmpty)return ""
  val bytes=ByteArrayOutputStream()
  DataOutputStream(bytes).use{out->
   val version=if(marks.any{it.signature!=null})2 else 1
   out.writeInt(version);out.writeInt(marks.size)
   for(m in marks){out.writeUTF(m.id);out.writeInt(m.tool.ordinal);with(m.bounds){out.writeFloat(left);out.writeFloat(top);out.writeFloat(right);out.writeFloat(bottom)}
    with(m.style){out.writeInt(rgb);out.writeFloat(opacity);out.writeFloat(stroke);out.writeFloat(textSize)}
    out.writeUTF(m.text);out.writeInt(m.points.size);m.points.forEach{out.writeFloat(it.x);out.writeFloat(it.y)}
    if(version==2){val bytes=m.signature?.encoding?.toByteArray(Charsets.US_ASCII)?:byteArrayOf();out.writeInt(bytes.size);out.write(bytes)}
   }
  }
  require(bytes.size()<=384*1024)
  return Base64.getEncoder().encodeToString(bytes.toByteArray())
 }
 override fun equals(other:Any?)=this===other||(other is AnnotationLayer&&fingerprint==other.fingerprint&&marks==other.marks)
 override fun hashCode()=fingerprint.hashCode()
 override fun toString()="AnnotationLayer(${marks.size})"
 companion object {
  val EMPTY=AnnotationLayer(emptyList())
  fun of(marks:List<AnnotationMark>):AnnotationLayer {
   require(marks.size<=128&&marks.map{it.id}.distinct().size==marks.size)
   require(marks.sumOf{it.points.size+(it.signature?.pointCount?:0)}<=16384&&marks.sumOf{it.text.length}<=32768)
   val layer=AnnotationLayer(Collections.unmodifiableList(marks.toList()));require(layer.encode().length<=512*1024);layer.fingerprint;layer.metadataBytes;return layer
  }
  fun decode(encoded:String,maximumVersion:Int=2):AnnotationLayer {
   if(encoded.isEmpty())return EMPTY
   require(encoded.length<=512*1024)
   try {
    val input=DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
    val version=input.readInt();require(version in 1..kotlin.math.min(2,maximumVersion));val count=input.readInt();require(count in 1..128)
    val marks=ArrayList<AnnotationMark>(count)
    repeat(count){
     val id=input.readUTF();val kind=input.readInt();require(kind in AnnotationTool.entries.indices&&(version>=2||kind<7))
     val bounds=AnnotationBounds(input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat())
     val style=AnnotationStyle(input.readInt(),input.readFloat(),input.readFloat(),input.readFloat())
     val text=input.readUTF();val points=input.readInt();require(points in 0..2048)
     val samples=List(points){AnnotationPoint(input.readFloat(),input.readFloat())}
     val ink=if(version==2){val n=input.readInt();require(n in 0..MAX_SIGNATURE_ENCODING&&n<=input.available());if(n==0)null else {val bytes=ByteArray(n);input.readFully(bytes);HandwrittenSignature.decode(String(bytes,Charsets.US_ASCII))}}else null
     marks+=AnnotationMark.create(id,AnnotationTool.entries[kind],bounds,style,samples,text,ink)
    }
    require(input.read()==-1);return of(marks)
   }catch(_:Exception){throw IllegalArgumentException("Invalid annotation layer")}
  }
 }
}
/** Fit/zoom/pan in displayed image pixels; rotation is applied separately to normalized points. */
class AnnotationViewport(val width:Float,val height:Float,imageWidth:Float,imageHeight:Float,val zoom:Float=1f,val panX:Float=0f,val panY:Float=0f) {
 private val scale=min(width/imageWidth,height/imageHeight)
 private val contentWidth=imageWidth*scale;private val contentHeight=imageHeight*scale
 init { require(listOf(width,height,imageWidth,imageHeight).all{it.isFinite()&&it>0});require(zoom.isFinite()&&zoom in 1f..4f);require(panX.isFinite()&&panY.isFinite()) }
 fun toDisplay(p:AnnotationPoint):Pair<Float,Float> =(width/2f+(p.x-.5f)*contentWidth*zoom+panX) to (height/2f+(p.y-.5f)*contentHeight*zoom+panY)
 fun toSource(x:Float,y:Float):AnnotationPoint? {
  val a=(x-width/2f-panX)/(contentWidth*zoom)+.5f;val b=(y-height/2f-panY)/(contentHeight*zoom)+.5f
  return if(a.isFinite()&&b.isFinite()&&a in 0f..1f&&b in 0f..1f)AnnotationPoint(a,b)else null
 }
}
