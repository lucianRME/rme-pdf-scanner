package org.synapseworks.pageharbor.integrity

internal data class SignatureResult(val format:String,val binding:String,val coverage:String,val detail:String,val certificate:String="NOT_CHECKED",val certificateTime:String="NOT_CHECKED"){
 fun report()="Format: $format\nSigned-byte cryptography: $binding\nCoverage: $coverage\n$detail\nSigner certificate: $certificate\nCertificate time: $certificateTime\nTrust: NOT_ASSESSED (0 configured anchors; embedded certificates are not trusted).\nTrusted timestamp: NOT_CHECKED. Claimed PDF/CMS time is not a trusted timestamp.\nRevocation/freshness/AIA/OCSP/CRL: NOT_CHECKED; no remote fetching.\nRevision legality, DocMDP, CAdES/PAdES policy conformance and document interpretation: NOT_CHECKED."
}
internal data class IntegrityPage(val number:Int,val width:Float,val height:Float,val rotation:Int,val text:String?,val png:java.io.File?,val pixels:IntArray?,val rasterWidth:Int=0,val rasterHeight:Int=0)
internal data class IntegrityDocument(val sha256:String,val bytes:Long,val pages:List<IntegrityPage>,val signatures:List<SignatureResult>,val limitation:String)
internal data class PageDifference(val page:Int,val text:String,val visual:String,val changed:Long?,val dimensions:String)
internal object IntegrityRules{
 const val INPUT=8L*1024*1024
 fun bytesEqual(a:java.io.File,b:java.io.File,check:()->Unit={}):Boolean{
  require(a.length() in 1..INPUT&&b.length() in 1..INPUT);if(a.length()!=b.length())return false
  java.io.DataInputStream(a.inputStream()).use{x->java.io.DataInputStream(b.inputStream()).use{y->val left=ByteArray(8192);val right=ByteArray(8192);var remaining=a.length();while(remaining>0){check();val n=minOf(8192L,remaining).toInt();x.readFully(left,0,n);y.readFully(right,0,n);for(i in 0 until n)if(left[i]!=right[i])return false;remaining-=n};check();require(x.read()==-1&&y.read()==-1);return true}}
 }
 fun range(values:List<Long>,size:Long):Long?{
  if(values.size!=4||values[0]!=0L||values[1]<=0||values[2]<=values[1]||values[2]>size||values[3]<0||values[3]>size-values[2])return null
  return values[2]+values[3]
 }
 fun compare(a:IntegrityDocument,b:IntegrityDocument):List<PageDifference> =(0 until maxOf(a.pages.size,b.pages.size)).map{i->val x=a.pages.getOrNull(i);val y=b.pages.getOrNull(i)
  if(x==null)PageDifference(i+1,"ADDED_PAGE","ADDED_PAGE",null,"Comparison only")else if(y==null)PageDifference(i+1,"MISSING_PAGE","MISSING_PAGE",null,"Baseline only")else{
   val text=when{ x.text==null||y.text==null->"NOT_CHECKED";x.text.isBlank()||y.text.isBlank()->"NO_NATIVE_TEXT: not textually verified";x.text==y.text->"EXTRACTED_TEXT_EQUAL";else->"EXTRACTED_TEXT_DIFFERENT"}
   val comparable=x.pixels!=null&&y.pixels!=null&&x.rasterWidth==y.rasterWidth&&x.rasterHeight==y.rasterHeight
   val count=if(comparable)x.pixels!!.indices.count{x.pixels[it]!=y.pixels!![it]}.toLong()else null
   PageDifference(i+1,text,if(!comparable)"NOT_COMPARABLE_AT_SAME_RASTER"else if(count==0L)"RENDERED_PIXELS_EQUAL"else"RENDERED_PIXELS_DIFFERENT",count,if(x.width==y.width&&x.height==y.height&&x.rotation==y.rotation)"SAME_PAGE_BOX_ROTATION"else"DIFFERENT_PAGE_BOX_OR_ROTATION")
  }}
 fun safe(s:String):String{require(s.length<=1024);return buildString{for(c in s)if(c.isSurrogate()||c.isISOControl()||c in '\u202a'..'\u202e'||c in '\u2066'..'\u2069')append("\\u%04x".format(c.code))else append(c)}}
}
