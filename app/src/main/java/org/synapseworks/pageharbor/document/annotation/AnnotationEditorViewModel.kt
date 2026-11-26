package org.synapseworks.pageharbor.document.annotation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.synapseworks.pageharbor.document.session.DocumentPage
import java.util.UUID

data class AnnotationDraft(val page:DocumentPage,val revision:Long,val owner:String,val layer:AnnotationLayer,val selected:String?=null,
 val tool:AnnotationTool=AnnotationTool.FREEHAND,val mode:AnnotationMode=AnnotationMode.SELECT,val error:String?=null,val validating:Boolean=true,val appearanceError:String?=null,val textInputs:Map<String,String> = emptyMap())
enum class AnnotationMode { SELECT,DRAW,PAN }
/** Only accepted complete layers leave this retained draft; text input/pointer samples stay local. */
class AnnotationEditorViewModel:ViewModel() {
 val signatures=org.synapseworks.pageharbor.document.signature.SignatureWorkspace(viewModelScope)
 var state by mutableStateOf<AnnotationDraft?>(null);private set
 private var releaseSource:(()->Unit)?=null
 private var validation:Job?=null
 private var dimensions:Pair<Int,Int>?=null
 fun open(page:DocumentPage,revision:Long,owner:String,onRelease:()->Unit={}){cancel();releaseSource=onRelease;dimensions=null;state=AnnotationDraft(page,revision,owner,page.annotations);validate()}
 fun cancel(){signatures.cancel();validation?.cancel();validation=null;state=null;dimensions=null;val release=releaseSource;releaseSource=null;release?.invoke()}
 fun current(page:DocumentPage?,revision:Long,owner:String)=state?.let{it.page===page&&it.revision==revision&&it.owner==owner}?:false
 fun dimensions(width:Int,height:Int){if(dimensions!=width to height){dimensions=width to height;validate()}}
 fun select(id:String?){state=state?.copy(selected=id,mode=AnnotationMode.SELECT)}
 fun mode(mode:AnnotationMode){state=state?.copy(mode=mode)}
 fun tool(tool:AnnotationTool){state=state?.copy(tool=tool,mode=AnnotationMode.DRAW)}
 fun error(message:String){state=state?.copy(error=message)}
 fun reset(){signatures.cancel();val s=state?:return;state=s.copy(layer=s.page.annotations,selected=null,error=null,textInputs=emptyMap());validate()}
 fun put(mark:AnnotationMark){val s=state?:return;try{val layer=s.layer.put(mark);state=s.copy(layer=layer,selected=mark.id,error=pendingError(s.textInputs,layer));validate()}catch(_:IllegalArgumentException){error("Annotation limit reached. Remove a mark or shorten its content.")}}
 private fun pendingError(inputs:Map<String,String>,layer:AnnotationLayer):String?=when {
  inputs.values.any{!AnnotationMark.supportedText(it)}->"Correct the unsupported or empty text before applying."
  inputs.any{(id,text)->layer.marks.firstOrNull{it.id==id}?.text!=text}->"The pending text exceeds layer limits. Shorten it or retry after removing a mark."
  else->null
 }
 fun text(id:String,value:String) {
  val s=state?:return;val mark=s.layer.marks.firstOrNull{it.id==id&&it.tool==AnnotationTool.TEXT}?:return
  if(value.length>4096){error("This edit exceeds 4096 characters and was not accepted. Shorten the text.");return}
  val inputs=s.textInputs+(id to value)
  state=s.copy(textInputs=inputs,error=pendingError(inputs,s.layer))
  if(AnnotationMark.supportedText(value))put(mark.withText(value))
 }
 fun delete(){val s=state?:return;val id=s.selected?:return;val layer=s.layer.remove(id);val inputs=s.textInputs-id;state=s.copy(layer=layer,selected=null,error=pendingError(inputs,layer),textInputs=inputs);validate()}
 fun add(bounds:AnnotationBounds=AnnotationBounds(.2f,.2f,.7f,.6f),points:List<AnnotationPoint> = emptyList()) {
  val s=state?:return
  try {
   put(AnnotationMark.create(UUID.randomUUID().toString(),s.tool,bounds,
    style=AnnotationStyle(opacity=if(s.tool==AnnotationTool.HIGHLIGHT).35f else 1f),
    points=if(s.tool==AnnotationTool.FREEHAND&&points.isEmpty())listOf(AnnotationPoint(0f,0f),AnnotationPoint(1f,1f))else points,
    text=if(s.tool==AnnotationTool.TEXT)"Text"else ""))
  }catch(_:IllegalArgumentException){error("This mark exceeds the supported geometry or point limit.")}
 }
 fun useSignature() {
  val s=state?:return
  if(signatures.state?.busy!=false)return
  val ink=signatures.ink()?:return signatures.error("Draw at least one stroke before using.")
  val size=dimensions?:return signatures.error("Wait for the page preview.")
  try {
   val ratio=ink.aspectRatio*size.second.toFloat()/size.first
   val width=kotlin.math.min(.5f,.5f*ratio);val height=width/ratio
   require(width>=.01f&&height>=.01f)
   val mark=AnnotationMark.create(UUID.randomUUID().toString(),AnnotationTool.SIGNATURE,AnnotationBounds(.25f,.25f,.25f+width,.25f+height),AnnotationStyle(rgb=0),signature=ink)
   val layer=s.layer.put(mark)
   state=s.copy(layer=layer,selected=mark.id,mode=AnnotationMode.SELECT,error=null)
   signatures.cancel();validate()
  }catch(_:IllegalArgumentException){signatures.error("Placement exceeds page limits. Remove marks or use a smaller drawing.")}
 }
 fun resizeSignature(mark:AnnotationMark,scale:Float) { put(mark.withBounds(mark.bounds.proportionallyResized(scale))) }
 fun rotateSignature(mark:AnnotationMark) {
  val ink=mark.signature?:return;val size=dimensions?:return
  val b=mark.bounds;var width=b.height*size.second/size.first;var height=b.width*size.first/size.second
  val scale=kotlin.math.min(1f,kotlin.math.min(1f/width,1f/height));width=(width*scale).coerceAtMost(1f);height=(height*scale).coerceAtMost(1f)
  if(width<.01f||height<.01f)return error("Rotated signature is too small to place.")
  val left=(b.left+b.width/2f-width/2f).coerceIn(0f,1f-width);val top=(b.top+b.height/2f-height/2f).coerceIn(0f,1f-height)
  put(AnnotationMark.create(mark.id,mark.tool,AnnotationBounds(left,top,(left+width).coerceAtMost(1f),(top+height).coerceAtMost(1f)),mark.style,signature=ink.rotated()))
 }
 private fun validate(){
  validation?.cancel();val s=state?:return;val size=dimensions
  if(size==null){state=s.copy(validating=true);return}
  state=s.copy(validating=true,appearanceError=null)
  validation=viewModelScope.launch {
   val message=withContext(Dispatchers.Default){AnnotationRenderer.validate(s.layer,size.first,size.second)}
   val current=state
   if(current!=null&&current.page===s.page&&current.layer===s.layer&&current.revision==s.revision)state=current.copy(validating=false,appearanceError=message)
  }
 }
 fun apply(commit:(DocumentPage,Long,String,AnnotationLayer)->Boolean):Boolean {
  val s=state?:return false
  if(signatures.state!=null||s.validating||s.appearanceError!=null||s.error!=null||pendingError(s.textInputs,s.layer)!=null)return false
  if(!commit(s.page,s.revision,s.owner,s.layer)){error("The page changed. Cancel and reopen the editor.");return false}
  cancel();return true
 }
 override fun onCleared(){cancel()}
}
