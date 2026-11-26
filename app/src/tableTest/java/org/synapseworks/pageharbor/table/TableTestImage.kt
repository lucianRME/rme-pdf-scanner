package org.synapseworks.pageharbor.table

import android.graphics.*
import java.io.ByteArrayOutputStream

/** Test-only renderer: literal coordinates/font frozen before parser implementation. */
internal object TableTestImage {
    fun bytes(values:List<List<String>>,rotation:Int=0):ByteArray {
        val base=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888)
        Canvas(base).apply{drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=54f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
            values.forEachIndexed{r,row->row.forEachIndexed{c,value->drawText(value,listOf(80f,650f,1180f)[c],100f+r*200f,paint)}}}
        val image=if(rotation==0)base else Bitmap.createBitmap(base,0,0,base.width,base.height,Matrix().apply{postRotate(-rotation.toFloat())},true)
        return try{ByteArrayOutputStream().also{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}finally{if(image!==base)image.recycle();base.recycle()}
    }
}
