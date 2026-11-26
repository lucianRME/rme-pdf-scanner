package org.synapseworks.pageharbor.document.session

/** Physical size of an upright single source page, before session rotation; PDF points (1/72in). */
data class DocumentPaperSize(val widthPoints: Float, val heightPoints: Float) {
    init { require(widthPoints.isFinite() && heightPoints.isFinite() && widthPoints in 1f..14_400f && heightPoints in 1f..14_400f) }
    fun rotated(degrees: Int): DocumentPaperSize { require(degrees in setOf(0,90,180,270)); return if(degrees%180==0)this else DocumentPaperSize(heightPoints,widthPoints) }
    fun matchesPixels(width: Int?,height: Int?): Boolean {
        if(width==null||height==null||width<=0||height<=0)return false
        val x=widthPoints/width;val y=heightPoints/height
        return kotlin.math.abs(x-y)<=maxOf(x,y)*.002f
    }
    fun encode(): String = "%08x:%08x".format(java.util.Locale.ROOT,widthPoints.toBits(),heightPoints.toBits())
    companion object {
        fun decode(value: String): DocumentPaperSize? {
            if(value.isEmpty())return null
            require(value.matches(Regex("[0-9a-f]{8}:[0-9a-f]{8}"))) { "Invalid physical page size" }
            return DocumentPaperSize(Float.fromBits(value.substring(0,8).toLong(16).toInt()),Float.fromBits(value.substring(9).toLong(16).toInt()))
        }
    }
}
