package org.synapseworks.pageharbor.document.annotation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import org.synapseworks.pageharbor.document.session.*

internal object AnnotationFixtures {
 fun layer():AnnotationLayer=AnnotationLayer.of(AnnotationTool.entries.filter{it!=AnnotationTool.SIGNATURE}.mapIndexed{i,t->
  AnnotationMark.create("fixture-$i",t,if(t==AnnotationTool.TEXT)AnnotationBounds(.08f,.65f,.9f,.95f)else AnnotationBounds(.1f+i*.04f,.1f+i*.035f,.5f+i*.04f,.5f+i*.035f),
   style=AnnotationStyle(rgb=if(t==AnnotationTool.HIGHLIGHT)0xFFFF00 else 0xD02020,opacity=if(t==AnnotationTool.HIGHLIGHT).35f else 1f,textSize=.035f),
   points=if(t==AnnotationTool.FREEHAND)listOf(AnnotationPoint(0f,0f),AnnotationPoint(.3f,.7f),AnnotationPoint(1f,1f))else emptyList(),text=if(t==AnnotationTool.TEXT)"Café Résumé\nÅngström naïve"else "")
 })
 fun source(root:File,id:Long=1):DocumentPage {
  val file=File(root,"source-$id.jpg");val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
  try{file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}}finally{bitmap.recycle()}
  return DocumentPage(DocumentPageId(id),DocumentResource(Uri.fromFile(file).toString(),DocumentResourceOwnership.USER_OR_EXTERNAL),DocumentSourceCategory.SELECTED_IMAGE,imageMetadata=DocumentImageMetadata(file.length(),800,600))
 }
}
