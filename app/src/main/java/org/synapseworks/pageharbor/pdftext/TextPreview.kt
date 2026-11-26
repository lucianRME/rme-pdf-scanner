package org.synapseworks.pageharbor.pdftext

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.synapseworks.pageharbor.forms.*
import java.io.File

internal fun textPreview(file:File,scratch:File,run:TextRun,check:()->Unit):Bitmap {
    check()
    val geometry=FormPdfEngine.load(file,scratch,FormBudget(check)).use{pdf->val p=pdf.getPage(run.page-1);val b=p.cropBox;floatArrayOf(b.lowerLeftX,b.lowerLeftY,b.width,b.height,p.rotation.toFloat())}
    check()
    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->renderer.openPage(run.page-1).use{page->
        check();val scale=1024f/maxOf(page.width,page.height);val w=(page.width*scale).toInt().coerceAtLeast(1);val h=(page.height*scale).toInt().coerceAtLeast(1)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
        try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);check()
            fun point(x:Float,y:Float):Pair<Float,Float>{val u=(x-geometry[0])/geometry[2];val v=1-(y-geometry[1])/geometry[3];return when(geometry[4].toInt()){90->1-v to u;180->1-u to 1-v;270->v to 1-u;else->u to v}}
            val corners=listOf(point(run.x,run.y-run.size*.3f),point(run.x+run.width,run.y+run.size))
            val rect=RectF(corners.minOf{it.first}*w,corners.minOf{it.second}*h,corners.maxOf{it.first}*w,corners.maxOf{it.second}*h)
            Canvas(bitmap).drawRect(rect,Paint().apply{color=Color.rgb(30,90,220);style=Paint.Style.STROKE;strokeWidth=2f})
            return bitmap
        }catch(t:Throwable){bitmap.recycle();throw t}
    }}}
}
internal fun textFailure(f:FormFailure)=when(f){
    FormFailure.UNSUPPORTED,FormFailure.MALFORMED,FormFailure.ACTIVE->"This PDF is outside the supported native text profile. Scans, OCR layers, forms, hidden or complex text, custom fonts and active/signed/restricted content cannot be edited here. The original is unchanged."
    FormFailure.FIT->"The replacement must differ from the original, use supported Western Latin characters and fit the original run at its original font size. Shorten it or return to selection."
    else->f.message
}
