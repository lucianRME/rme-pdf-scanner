package org.synapseworks.pageharbor.assistance

import java.util.Locale
import org.synapseworks.pageharbor.translation.TranslationUtf8

class AssistancePage(val number:Int,val id:String,val text:String){override fun toString()="AssistancePage(<redacted>)"}
class Passage(val page:AssistancePage,val start:Int,val end:Int,val complete:Boolean){val quote get()=page.text.substring(start,end);val citation get()="Page ${page.number} [${page.id}], UTF-16 $start–$end";override fun toString()="Passage(<redacted>)"}
class AssistanceIndex(val revision:Long,val pages:List<AssistancePage>,check:()->Unit={}){
 val passages:List<Passage>
 private val postings:Map<String,Set<Int>>
 init{
  require(pages.size in 1..3&&pages.map{it.id}.distinct().size==pages.size&&pages.all{it.text.isNotBlank()})
  require(pages.sumOf{TranslationUtf8.encode(it.text).size}<=32768&&pages.sumOf{it.text.length}<=32768)
  val units=mutableListOf<Passage>();val index=linkedMapOf<String,MutableSet<Int>>();var tokenCount=0
  for(page in pages){var start=0;var eligibleStart=true
   fun emit(end:Int,complete:Boolean){var a=start;var b=end;while(a<b&&page.text[a].isWhitespace())a++;while(b>a&&page.text[b-1].isWhitespace())b--;if(a<b){require(units.size<512){"UNIT_LIMIT"};val p=Passage(page,a,b,complete&&eligibleStart);val id=units.size;units+=p;for(t in terms(p.quote)){check();require(++tokenCount<=8192){"TOKEN_LIMIT"};index.getOrPut(t){linkedSetOf()}.add(id);require(index.size<=4096){"TERM_LIMIT"}}};start=end;if(a<b)eligibleStart=complete}
   for(i in page.text.indices){check();val c=page.text[i];if(c in ".!?"&&(i==page.text.lastIndex||page.text[i+1].isWhitespace())&&!abbreviation(page.text,i))emit(i+1,true) else if(c=='\n')emit(i+1,false)}
   if(start<page.text.length)emit(page.text.length,false)
  };passages=units.toList();postings=index.mapValues{it.value.toSet()}
 }
 fun find(query:String,check:()->Unit={}):List<Passage>{require(query.toByteArray(Charsets.UTF_8).size<=128);TranslationUtf8.encode(query);val terms=terms(query).distinct();require(terms.size in 1..8){"QUERY_PROFILE"};var ids:Set<Int>?=null
  for(t in terms){check();ids=ids?.intersect(postings[t].orEmpty())?:postings[t].orEmpty()};check();require(ids.orEmpty().size<=8){"REFINE_QUERY"};return ids.orEmpty().sorted().map{passages[it]}
 }
 fun summary():List<Passage> = passages.filter{it.complete}.take(5)
 fun title():Passage? = passages.firstOrNull{it.complete&&it.quote.toByteArray(Charsets.UTF_8).size<=120}
 fun tags():List<Passage>{val ranked=postings.entries.filter{it.key.codePointCount(0,it.key.length)>=4}.sortedWith(compareByDescending<Map.Entry<String,Set<Int>>>{it.value.size}.thenBy{it.key}).take(5);return ranked.map{e->val p=passages[e.value.minOrNull()!!];val token=tokenRanges(p.quote).first{it.third==e.key};Passage(p.page,p.start+token.first,p.start+token.second,false)}}
 companion object{
  private fun abbreviation(s:String,i:Int):Boolean{if(s[i]!='.')return false;var a=i;while(a>0&&s[a-1].isLetter())a--;val word=s.substring(a,i).lowercase(Locale.ROOT);return word.length==1||word in setOf("mr","mrs","ms","dr","prof","sr","jr","etc","vs")}
  private fun tokenRanges(s:String):List<Triple<Int,Int,String>>{val out=mutableListOf<Triple<Int,Int,String>>();var start=-1;var i=0;while(i<s.length){val cp=s.codePointAt(i);if(Character.isLetterOrDigit(cp)){if(start<0)start=i}else if(start>=0){out+=Triple(start,i,s.substring(start,i).lowercase(Locale.ROOT));start=-1};i+=Character.charCount(cp)};if(start>=0)out+=Triple(start,s.length,s.substring(start).lowercase(Locale.ROOT));return out}
  fun terms(s:String):List<String> = tokenRanges(s).map{it.third}
 }
}
