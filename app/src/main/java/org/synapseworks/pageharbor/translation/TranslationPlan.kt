package org.synapseworks.pageharbor.translation

/** UTF-16 source offsets, always at Unicode scalar boundaries. No text in diagnostics. */
class TranslationPage(val id:String,val text:String){override fun toString()="TranslationPage(<redacted>)"}
class TranslationPiece(val page:Int,val start:Int,val end:Int,val source:String,val separator:Boolean,val pageId:String=""){override fun toString()="TranslationPiece(<redacted>)"}
object TranslationPlan {
 const val MAX_PAGES=3;const val MAX_BYTES=8192;const val MAX_SEGMENTS=32;const val MAX_PIECES=128
 fun plan(pages:List<TranslationPage>):List<TranslationPiece>{
  require(pages.size in 1..MAX_PAGES && pages.sumOf{it.text.length}<=MAX_BYTES && pages.sumOf{it.text.toByteArray(Charsets.UTF_8).size}<=MAX_BYTES)
  val out=mutableListOf<TranslationPiece>()
  pages.forEachIndexed{page,p->var i=0
   while(i<p.text.length){val start=i
    if(p.text[i].isWhitespace()){while(i<p.text.length&&p.text[i].isWhitespace())i++;out+=TranslationPiece(page,start,i,p.text.substring(start,i),true,p.id)}
    else{var bytes=0;var end=i;var wordEnd=-1
     while(end<p.text.length){val cp=Character.codePointAt(p.text,end);require(cp !in 0xD800..0xDFFF);val n=String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size;if(bytes+n>256)break
      if(p.text[end]=='\r'||p.text[end]=='\n')break
      bytes+=n;end+=Character.charCount(cp)
      if(Character.isWhitespace(cp))wordEnd=end-Character.charCount(cp)
      if(cp in listOf(46,33,63) && (end==p.text.length||p.text[end].isWhitespace()))break
     }
     require(end>start);if(end<p.text.length&&!p.text[end].isWhitespace()&&wordEnd>start)end=wordEnd
     while(end>start&&p.text[end-1].isWhitespace())end--
     require(end>start);out+=TranslationPiece(page,start,end,p.text.substring(start,end),false,p.id);i=end
    }
    require(out.size<=MAX_PIECES)
   }
  };require(out.count{!it.separator} in 1..MAX_SEGMENTS);return out
 }
 fun split(p:TranslationPiece):List<TranslationPiece>{require(!p.separator&&p.source.codePointCount(0,p.source.length)>1)
  var m=p.source.offsetByCodePoints(0,p.source.codePointCount(0,p.source.length)/2);val space=p.source.lastIndexOf(' ',m);if(space>0)m=space
  val left=p.source.substring(0,m);val right=p.source.substring(m);val pieces=plan(listOf(TranslationPage("split",left),TranslationPage("split",right)))
  return pieces.map{val offset=if(it.page==0)0 else m;TranslationPiece(p.page,p.start+offset+it.start,p.start+offset+it.end,it.source,it.separator,p.pageId)}
 }
}
