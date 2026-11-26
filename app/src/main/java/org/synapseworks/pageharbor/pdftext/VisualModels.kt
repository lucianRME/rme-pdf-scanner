package org.synapseworks.pageharbor.pdftext

import org.synapseworks.pageharbor.document.annotation.AnnotationBounds
import org.synapseworks.pageharbor.document.annotation.AnnotationPoint
import org.synapseworks.pageharbor.forms.*

internal enum class PdfEditMode { NATIVE,VISUAL }
internal data class VisualPage(val x:Float,val y:Float,val width:Float,val height:Float,val rotation:Int) {
    val displayWidth get()=if(rotation%180==0)width else height
    val displayHeight get()=if(rotation%180==0)height else width
    fun source(point:AnnotationPoint)=point.rotated((360-rotation)%360)
}
internal data class VisualDocument(val pages:List<VisualPage>) {
    fun form()=FormModel(listOf(FormField("visual","Visual replacement",FormKind.TEXT,listOf(""),false,false,pages.indices.map{it+1},maxLength=256)),pages.size)
    override fun toString()="VisualDocument(<redacted>)"
}
internal data class VisualEdit(val page:Int,val region:AnnotationBounds,val text:String) {
    override fun toString()="VisualEdit(<redacted>)"
}
internal val DEFAULT_VISUAL_REGION=AnnotationBounds(.05f,.35f,.95f,.55f)
internal fun visualTextAllowed(text:String)=text.isNotBlank()&&text.length<=256&&FormLimits.text(text,false)

internal fun visualFailure(f:FormFailure)=when(f){
    FormFailure.FIT->"Use 1–256 supported Western Latin characters in one line. The text must fit the region at 12pt with 4pt margins; region height must be at least 24pt. Enlarge the region or shorten the text."
    FormFailure.UNSUPPORTED,FormFailure.MALFORMED,FormFailure.ACTIVE->"This PDF or region is outside the visual replacement profile. Use a plain white border; colored or textured borders, unsafe documents, forms, unusual fonts/images and unsupported structures are declined. The original is unchanged."
    else->f.message
}
