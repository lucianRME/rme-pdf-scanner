package org.synapseworks.pageharbor.translation

class RomanianDraftChange(val offset:Int,val from:Char,val to:Char,val context:String){override fun toString()="RomanianDraftChange(<redacted>)"}
object RomanianDraftCorrection {
 private val letters=mapOf('Ş' to 'Ș','ş' to 'ș','Ţ' to 'Ț','ţ' to 'ț')
 fun propose(text:String):List<RomanianDraftChange>{TranslationUtf8.encode(text);return text.mapIndexedNotNull{i,c->letters[c]?.let{RomanianDraftChange(i,c,it,context(text,i))}}.also{require(it.size<=256){"CORRECTION_PREVIEW_LIMIT"}}}
 private fun context(s:String,i:Int):String{var a=(i-16).coerceAtLeast(0);var b=(i+17).coerceAtMost(s.length);if(a>0&&s[a].isLowSurrogate()&&s[a-1].isHighSurrogate())a--;if(b<s.length&&b>0&&s[b-1].isHighSurrogate()&&s[b].isLowSurrogate())b++;return s.substring(a,b)}
 fun apply(text:String,selected:Set<Int>):String{val changes=propose(text).associateBy{it.offset};require(selected.all{it in changes});return text.mapIndexed{i,c->if(i in selected)changes.getValue(i).to else c}.joinToString("")}
}
