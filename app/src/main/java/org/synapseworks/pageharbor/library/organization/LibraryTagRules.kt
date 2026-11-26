package org.synapseworks.pageharbor.library.organization

import java.text.Normalizer
import java.util.Locale

/** Labels are metadata, never paths or filename fragments. Normalization is locale-independent. */
data class LibraryTagName(val display:String,val key:String)
object LibraryTagRules {
    const val MAX_TAGS=256
    const val MAX_DOCUMENT_TAGS=32
    const val MAX_FILTER_TAGS=16
    const val PAGE_SIZE=30
    fun name(raw:String):LibraryTagName? {
        if(raw.length>160)return null
        val value=Normalizer.normalize(raw,Normalizer.Form.NFKC)
        val out=StringBuilder();var space=false;var index=0
        while(index<value.length){
            val cp=value.codePointAt(index);index+=Character.charCount(cp)
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){space=out.isNotEmpty();continue}
            if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT.toInt()||cp in 0xD800..0xDFFF)return null
            if(space){out.append(' ');space=false};out.appendCodePoint(cp)
        }
        val display=out.toString()
        if(display.codePointCount(0,display.length) !in 1..40)return null
        return LibraryTagName(display,display.lowercase(Locale.ROOT))
    }
    fun search(raw:String):String {
        val value=Normalizer.normalize(raw.take(160),Normalizer.Form.NFKC)
        val out=StringBuilder();var space=false
        value.codePoints().forEach { cp ->
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp))space=out.isNotEmpty()
            else {if(space){out.append(' ');space=false};out.appendCodePoint(cp)}
        }
        return out.toString().lowercase(Locale.ROOT)
    }
    fun like(raw:String):String="%"+search(raw).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
}
enum class TagMatchMode { ANY, ALL }
data class LibraryOrganizationFilter(val tagIds:Set<String> = emptySet(),val match:TagMatchMode=TagMatchMode.ANY,val favoritesOnly:Boolean=false) {
    init{require(tagIds.size<=LibraryTagRules.MAX_FILTER_TAGS);require(tagIds.all{it.length in 1..128})}
}
enum class OrganizationFailure { INVALID_NAME, NAME_EXISTS, TAG_LIMIT, ASSIGNMENT_LIMIT, NOT_FOUND, UNAVAILABLE, BUSY }
sealed interface OrganizationResult {
    data class Saved(val tagId:String?=null):OrganizationResult
    data class Failed(val reason:OrganizationFailure):OrganizationResult
}
internal class OrganizationConstraint(val reason:OrganizationFailure):RuntimeException()
