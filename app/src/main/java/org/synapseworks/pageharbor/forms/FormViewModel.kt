package org.synapseworks.pageharbor.forms

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import kotlinx.coroutines.*

internal class FormViewModel(app:Application):AndroidViewModel(app){
    val activityResultOwner=java.util.UUID.randomUUID().toString()
    private val controller=FormController();private var source:FormHandle?=null;private var output:File?=null
    private var job:Job?=null;private var deferredInput:Pair<FormToken,Uri>?=null
    private var deferredSave:Triple<FormToken,Uri,()->Boolean>?=null
    internal var onSavedForTest:((Uri)->Unit)?=null
    var state:FormState? by mutableStateOf(null);private set
    init{viewModelScope.launch(Dispatchers.IO){FormWork.run{runCatching{FormWork.orphans(app)}}}}
    private fun publish(){state=controller.state}
    fun open(){controller.open();publish()}
    private fun dropOutput(){source?.drop(output);output=null}
    private fun dropSource(){dropOutput();source?.close();source=null}
    fun close(){controller.close();job?.cancel();deferredInput=null;deferredSave=null;dropSource();publish()}
    fun setActive(value:Boolean){controller.active(value);if(value){deferredInput?.let{deferredInput=null;read(it.first,it.second)};deferredSave?.let{deferredSave=null;write(it.first,it.second,it.third)}}}
    fun background(recreating:Boolean){val keep=controller.background(recreating);job?.cancel();if(!keep){deferredInput=null;deferredSave=null;dropSource()};publish()}
    fun lock(){controller.lock(deferredInput!=null);job?.cancel();deferredSave=null;if(controller.state==null){deferredInput=null;dropSource()};publish()}
    fun beginInput(owner:String,epoch:Long):Boolean {
        val yes=controller.beginInput(owner,epoch);if(yes){job?.cancel();deferredInput=null;deferredSave=null;dropSource()};publish();return yes
    }
    fun picked(uri:Uri?){val t=controller.picked(uri!=null);publish();if(t!=null&&uri!=null){if(controller.matches(t,FormPhase.READING))read(t,uri)else deferredInput=t to uri}}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    private fun read(t:FormToken,uri:Uri){if(!controller.matches(t,FormPhase.READING))return
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){var owned:FormHandle?=null
            try {
                val model=withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!controller.matches(t,FormPhase.READING))throw CancellationException("Form session ended")}
                    check();PDFBoxResourceLoader.init(getApplication<Application>())
                    val handle=FormHandle.capture(getApplication(),uri,check);owned=handle
                    FormPdfEngine.inspect(handle.input,handle.scratch,check)
                }}
                if(controller.matches(t,FormPhase.READING)){source=owned;owned=null;controller.loaded(t,model);publish()}
            }catch(e:CancellationException){throw e}catch(e:FormException){controller.loaded(t,null,e.reason);publish()}
            catch(_:InvalidPasswordException){controller.loaded(t,null,FormFailure.ENCRYPTED);publish()}
            catch(_:OutOfMemoryError){controller.loaded(t,null,FormFailure.LIMIT);publish()}
            catch(_:StackOverflowError){controller.loaded(t,null,FormFailure.LIMIT);publish()}
            catch(_:Exception){controller.loaded(t,null,FormFailure.MALFORMED);publish()}
            finally{owned?.close()}
        }
    }
    fun edit(owner:String,epoch:Long,id:String,values:List<String>){controller.edit(owner,epoch,id,values);publish()}
    fun review(owner:String,epoch:Long){controller.review(owner,epoch);publish()}
    fun correct(){controller.correct();if(controller.state?.phase==FormPhase.EDIT)dropOutput();publish()}
    fun confirm(owner:String,epoch:Long,id:String,value:Boolean){controller.confirm(owner,epoch,id,value);publish()}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    fun prepare(owner:String,epoch:Long,id:String){val t=controller.prepare(owner,epoch,id)?:return;val s=controller.state!!;val handle=source;val lease=handle?.lease();publish()
        if(handle==null||lease==null){controller.prepared(t,FormFailure.MALFORMED);publish();return}
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){var file:File?=null;var adopted=false
            try {
                withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!controller.matches(t,FormPhase.PREPARING))throw CancellationException("Form preparation ended")}
                    check();val out=handle.output();file=out
                    FormPdfEngine.prepare(handle.input,out,handle.scratch,s.model!!,s.review!!,check)
                }}
                if(controller.matches(t,FormPhase.PREPARING)){output=file;adopted=true;controller.prepared(t,null);publish()}
            }catch(e:CancellationException){throw e}catch(e:FormException){controller.prepared(t,e.reason);publish()}
            catch(_:OutOfMemoryError){controller.prepared(t,FormFailure.LIMIT);publish()}
            catch(_:StackOverflowError){controller.prepared(t,FormFailure.LIMIT);publish()}
            catch(_:Exception){controller.prepared(t,FormFailure.UNSUPPORTED);publish()}
            finally{if(!adopted)handle.drop(file);lease.close()}
        }
    }
    fun beginSave(owner:String,epoch:Long,id:String):Boolean=controller.beginSave(owner,epoch,id).also{publish()}
    fun pickerFailed(){controller.destination(false);publish()}
    fun cancelWork(){controller.cancelWork();job?.cancel();deferredSave=null;publish()}
    fun destination(uri:Uri?,allowed:()->Boolean={true}){val t=controller.destination(uri!=null);publish();if(t!=null&&uri!=null){if(!write(t,uri,allowed))deferredSave=Triple(t,uri,allowed)}}
    @OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
    private fun write(t:FormToken,uri:Uri,allowed:()->Boolean):Boolean {
        if(!controller.startWrite(t))return false;publish()
        val handle=source;val file=output;val lease=handle?.lease()
        if(handle==null||file==null||lease==null){controller.saved(t,FormFailure.MALFORMED);publish();return true}
        job=viewModelScope.launch(start=CoroutineStart.ATOMIC){
            try {
                withContext(Dispatchers.IO){val context=currentCoroutineContext();FormWork.run{
                    val check={context.ensureActive();if(!allowed()||!controller.matches(t,FormPhase.SAVING))throw CancellationException("Form save ended")}
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
