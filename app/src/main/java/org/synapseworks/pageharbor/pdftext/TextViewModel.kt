package org.synapseworks.pageharbor.pdftext

import org.synapseworks.pageharbor.forms.*
import org.synapseworks.pageharbor.document.annotation.AnnotationBounds

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import kotlinx.coroutines.*

internal class TextViewModel(app:Application):AndroidViewModel(app){
    var mode:PdfEditMode by mutableStateOf(PdfEditMode.NATIVE);private set
    var visualDocument:VisualDocument? by mutableStateOf(null);private set
    var visualPage:Int by mutableStateOf(0);private set
    var visualRegion:AnnotationBounds? by mutableStateOf(null);private set
    var visualReview:VisualEdit? by mutableStateOf(null);private set
    var document:TextDocument? by mutableStateOf(null);private set
    var selected:String? by mutableStateOf(null);private set
    var preview:android.graphics.Bitmap? by mutableStateOf(null);private set
    var previewFailure:FormFailure? by mutableStateOf(null);private set
    private var previewJob:Job?=null
    private var previewEpoch=0L
    val activityResultOwner=java.util.UUID.randomUUID().toString()
    private val controller=FormController();private var source:FormHandle?=null;private var output:File?=null
    private var job:Job?=null;private var deferredInput:Pair<FormToken,Uri>?=null
    private var deferredSave:Triple<FormToken,Uri,()->Boolean>?=null
    internal var onSavedForTest:((Uri)->Unit)?=null
    var state:FormState? by mutableStateOf(null);private set
    init{viewModelScope.launch(Dispatchers.IO){FormWork.run{runCatching{FormWork.orphans(app)}}}}
    private fun publish(){state=controller.state}
    fun open(){if(controller.state==null)mode=PdfEditMode.NATIVE;controller.open();publish()}
    private fun dropOutput(){source?.drop(output);output=null;preview=null;previewFailure=null;previewJob?.cancel();previewEpoch++}
    private fun dropSource(){dropOutput();source?.close();source=null;document=null;selected=null;visualDocument=null;visualPage=0;visualRegion=null;visualReview=null}
    fun close(){controller.close();job?.cancel();previewJob?.cancel();deferredInput=null;deferredSave=null;dropSource();mode=PdfEditMode.NATIVE;publish()}
    fun setActive(value:Boolean){controller.active(value);if(value){deferredInput?.let{deferredInput=null;read(it.first,it.second)};deferredSave?.let{deferredSave=null;write(it.first,it.second,it.third)}}}
    fun background(recreating:Boolean){val keep=controller.background(recreating);job?.cancel();if(!keep){deferredInput=null;deferredSave=null;dropSource()};publish()}
    fun lock(){controller.lock(deferredInput!=null);job?.cancel();deferredSave=null;if(controller.state==null){deferredInput=null;dropSource()};publish()}
    fun beginInput(owner:String,epoch:Long):Boolean {
        val yes=controller.beginInput(owner,epoch);if(yes){job?.cancel();deferredInput=null;deferredSave=null;dropSource()};publish();return yes
    }
    fun chooseMode(value:PdfEditMode){if(state?.phase !in setOf(FormPhase.OPEN,FormPhase.EDIT,FormPhase.REVIEW,FormPhase.READY))return;close();mode=value;controller.open();publish()}
    fun chooseVisualPage(index:Int){if(state?.phase!=FormPhase.EDIT||index !in (visualDocument?.pages?.indices?:IntRange.EMPTY))return;visualPage=index;visualRegion=null;showSource()}
    fun chooseVisualRegion(value:AnnotationBounds){if(mode==PdfEditMode.VISUAL&&state?.phase==FormPhase.EDIT)visualRegion=value}
    fun picked(uri:Uri?){val t=controller.picked(uri!=null);publish();if(t!=null&&uri!=null){if(controller.matches(t,FormPhase.READING))read(t,uri)else deferredInput=t to uri}}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    private fun read(t:FormToken,uri:Uri){if(!controller.matches(t,FormPhase.READING))return
        val inputMode=mode
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){var owned:FormHandle?=null
            try {
                val model=withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!controller.matches(t,FormPhase.READING))throw CancellationException("Text session ended")}
                    check();PDFBoxResourceLoader.init(getApplication<Application>())
                    val handle=FormHandle.capture(getApplication(),uri,check);owned=handle
                    if(inputMode==PdfEditMode.VISUAL)null to VisualPdfEngine.inspect(handle.input,handle.scratch,check) else PdfTextEngine.inspect(handle.input,handle.scratch,check) to null
                }}
                if(controller.matches(t,FormPhase.READING)){source=owned;owned=null;document=model.first;visualDocument=model.second;selected=if(inputMode==PdfEditMode.VISUAL)"visual"else null;controller.loaded(t,model.first?.form()?:model.second!!.form());publish();if(inputMode==PdfEditMode.VISUAL)showSource()}
            }catch(e:CancellationException){throw e}catch(e:FormException){controller.loaded(t,null,e.reason);publish()}
            catch(_:InvalidPasswordException){controller.loaded(t,null,FormFailure.ENCRYPTED);publish()}
            catch(_:OutOfMemoryError){controller.loaded(t,null,FormFailure.LIMIT);publish()}
            catch(_:StackOverflowError){controller.loaded(t,null,FormFailure.LIMIT);publish()}
            catch(_:Exception){controller.loaded(t,null,FormFailure.MALFORMED);publish()}
            finally{owned?.close()}
        }
    }
    fun select(id:String){val s=state?:return;if(s.phase!=FormPhase.EDIT||document?.runs?.none{it.id==id}!=false)return
        selected=id
        val initial=s.model!!.initial();initial.forEach{(key,value)->controller.edit(s.owner,s.epoch,key,value)};publish();showSource()
    }
    fun edit(value:String){val s=state?:return;val id=selected?:return;controller.edit(s.owner,s.epoch,id,listOf(value));publish()}
    fun review(){val s=state?:return;if(selected==null||mode==PdfEditMode.VISUAL&&visualRegion==null)return;previewJob?.cancel();previewEpoch++;preview=null
        controller.review(s.owner,s.epoch);publish();val r=state?.review?:return
        if(mode==PdfEditMode.VISUAL)visualReview=VisualEdit(visualPage,visualRegion!!,r.values["visual"]!!.single())
        controller.confirm(s.owner,s.epoch,r.id,true);prepare(s.owner,s.epoch,r.id)
    }
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    private fun showSource(){val s=state?:return;val handle=source?:return;val run=document?.runs?.singleOrNull{it.id==selected};val page=visualPage;val isVisual=mode==PdfEditMode.VISUAL;if(run==null&&!isVisual)return;val lease=handle.lease()?:return
        previewJob?.cancel();val generation=++previewEpoch;preview=null;previewFailure=null
        previewJob=viewModelScope.launch(start=CoroutineStart.ATOMIC){try{val bitmap=withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{val check={context.ensureActive();if(generation!=previewEpoch)throw CancellationException("Text preview ended")};if(isVisual)VisualPdfEngine.preview(handle.input,page,check)else textPreview(handle.input,handle.scratch,run!!,check)}};if(generation==previewEpoch&&controller.matches(FormToken(s.owner,s.epoch),FormPhase.EDIT))preview=bitmap}
            catch(e:CancellationException){throw e}catch(_:OutOfMemoryError){if(generation==previewEpoch)previewFailure=FormFailure.LIMIT}catch(_:StackOverflowError){if(generation==previewEpoch)previewFailure=FormFailure.LIMIT}catch(_:Exception){if(generation==previewEpoch)previewFailure=FormFailure.UNSUPPORTED}finally{lease.close()}}
    }
    fun correct(){controller.correct();if(controller.state?.phase==FormPhase.EDIT){dropOutput();visualReview=null;showSource()};publish()}
    fun confirm(owner:String,epoch:Long,id:String,value:Boolean){controller.confirm(owner,epoch,id,value);publish()}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    fun prepare(owner:String,epoch:Long,id:String){val t=controller.prepare(owner,epoch,id)?:return;val s=controller.state!!;val handle=source;val lease=handle?.lease();publish()
        if(handle==null||lease==null){controller.prepared(t,FormFailure.MALFORMED);publish();return}
        val expected=document;val run=expected?.runs?.single{it.id==selected};val replacement=run?.let{s.review!!.values[it.id]!!.single()};val visual=visualReview;val visualModel=visualDocument
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){var file:File?=null;var adopted=false;var rendered:android.graphics.Bitmap?=null
            try {
                withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!controller.matches(t,FormPhase.PREPARING))throw CancellationException("Text preparation ended")}
                    check();val out=handle.output();file=out
                    if(visual!=null&&visualModel!=null){VisualPdfEngine.prepare(handle.input,out,handle.scratch,visualModel,visual,check);rendered=VisualPdfEngine.preview(out,visual.page,check)}
                    else {PdfTextEngine.prepare(handle.input,out,handle.scratch,expected!!,run!!.id,replacement!!,check)
                        val before=textPreview(handle.input,handle.scratch,run,check)
                        try{val after=textPreview(out,handle.scratch,run,check);if(before.sameAs(after)){after.recycle();throw FormException(FormFailure.UNSUPPORTED)};rendered=after}finally{before.recycle()}}

                }}
                if(controller.matches(t,FormPhase.PREPARING)){output=file;preview=rendered;adopted=true;controller.prepared(t,null);controller.confirm(owner,epoch,id,false);publish()}
            }catch(e:CancellationException){throw e}catch(e:FormException){controller.prepared(t,e.reason);publish()}
            catch(_:OutOfMemoryError){controller.prepared(t,FormFailure.LIMIT);publish()}
            catch(_:StackOverflowError){controller.prepared(t,FormFailure.LIMIT);publish()}
            catch(_:Exception){controller.prepared(t,FormFailure.UNSUPPORTED);publish()}
            finally{if(!adopted)handle.drop(file);lease.close()}
        }
    }
    fun beginSave(owner:String,epoch:Long,id:String):Boolean=controller.beginSave(owner,epoch,id).also{publish()}
    fun pickerFailed(){controller.destination(false);publish()}
    fun cancelWork(){if(state?.phase in setOf(FormPhase.PICKING,FormPhase.READING)){close();return};controller.cancelWork();job?.cancel();deferredSave=null;publish()}
    fun destination(uri:Uri?,allowed:()->Boolean={true}){val t=controller.destination(uri!=null);publish();if(t!=null&&uri!=null){if(!write(t,uri,allowed))deferredSave=Triple(t,uri,allowed)}}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    private fun write(t:FormToken,uri:Uri,allowed:()->Boolean):Boolean {
        if(!controller.startWrite(t))return false;publish()
        val handle=source;val file=output;val lease=handle?.lease()
        if(handle==null||file==null||lease==null){controller.saved(t,FormFailure.MALFORMED);publish();return true}
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){
            try {
                withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!allowed()||!controller.matches(t,FormPhase.SAVING))throw CancellationException("Text save ended")}
                    check();if(!FormDestination.supports(getApplication(),uri))throw FormException(FormFailure.DESTINATION)
                    FormDestination.write(handle,file,uri,{getApplication<Application>().contentResolver.openFileDescriptor(uri,"rw")},check)
                }}
                if(controller.matches(t,FormPhase.SAVING)){controller.saved(t,null);publish();onSavedForTest?.invoke(uri)}
            }catch(e:CancellationException){controller.saved(t,FormFailure.WRITE);publish();throw e}
            catch(e:FormException){controller.saved(t,e.reason);publish()}
            catch(_:OutOfMemoryError){controller.saved(t,FormFailure.LIMIT);publish()}
            catch(_:Exception){controller.saved(t,FormFailure.WRITE);publish()}
            finally{lease.close()}
        };return true
    }
    override fun onCleared(){close();super.onCleared()}
}
