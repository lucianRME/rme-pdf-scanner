package org.synapseworks.pageharbor.forms

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

class FormPdfEngineInstrumentedTest {
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext
    private lateinit var dir:File
    private val expected get()=JSONObject(i.context.assets.open("m23/expected.json").bufferedReader().use{it.readText()})
    private fun values(kind:String):Map<String,List<String>>{val obj=expected.getJSONObject(kind);return obj.keys().asSequence().associateWith{k->obj.getJSONArray(k).let{v->(0 until v.length()).map{v.getString(it)}}}}
    @Before fun setup(){PDFBoxResourceLoader.init(app);dir=File(app.cacheDir,"m23-engine-${java.util.UUID.randomUUID()}").apply{mkdirs()}}
    @After fun cleanup(){assertTrue(dir.deleteRecursively())}
    private fun fixture(name:String)=File(dir,name).apply{i.context.assets.open("m23/$name").use{input->outputStream().use{input.copyTo(it)}}}
    private fun scratch()=File(dir,"scratch").apply{mkdirs()}
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()
    @Test fun frozenFieldsCurrentValuesAndGroupedWidgetsAreUsable(){
        val source=fixture("compatible.pdf");val before=hash(source);val m=FormPdfEngine.inspect(source,scratch())
        assertEquals(7,m.fields.size);assertEquals(2,m.pages);assertEquals(values("initial"),m.initial())
        assertEquals(listOf(1,2),m.fields.single{it.id=="customer.name"}.pages);assertEquals(listOf(1,2),m.fields.single{it.id=="consent"}.pages)
        assertTrue(m.fields.single{it.id=="reference"}.readOnly);assertEquals(listOf("United Kingdom","România"),m.fields.single{it.id=="country"}.options.map{it.label});assertEquals(before,hash(source))
    }
    @Test fun filledValuesAndEveryWidgetRemainEditableWithExplicitAppearances(){
        for(name in listOf("compatible.pdf","rotated.pdf")){
            val source=fixture(name);val before=hash(source);val m=FormPdfEngine.inspect(source,scratch());val review=FormReview.create(m,values("filled"))!!;val out=File(dir,"filled-$name")
            FormPdfEngine.prepare(source,out,scratch(),m,review)
            assertEquals(values("filled"),FormPdfEngine.inspect(out,scratch()).initial());assertEquals(before,hash(source))
            File(app.cacheDir,"m23-$name").writeBytes(out.readBytes())
        }
    }
    @Test fun allFrozenUnsupportedInputsDeclineWithoutChangingBytes(){
        val names=expected.getJSONArray("declined")
        for(n in 0 until names.length()){
            val source=fixture(names.getString(n)+".pdf");val before=hash(source);var declined=false
            try{FormPdfEngine.inspect(source,scratch())}catch(_:Exception){declined=true}
            assertTrue("Frozen unsupported case index $n must decline",declined);assertEquals(before,hash(source))
        }
    }
    @Test fun unsupportedTextAndClippingCannotCreateASaveableCopy(){
        val source=fixture("compatible.pdf");val model=FormPdfEngine.inspect(source,scratch());val good=values("filled")
        assertNull(FormReview.create(model,good+("customer.name" to listOf("😀"))))
        val long=good+("customer.name" to listOf("W".repeat(80)));val review=FormReview.create(model,long)!!
        try{FormPdfEngine.prepare(source,File(dir,"long.pdf"),scratch(),model,review);fail()}catch(e:FormException){assertEquals(FormFailure.FIT,e.reason)}
    }
    @Test fun cancellationStopsParsingAndPreparationWithoutSourceMutation(){
        val source=fixture("compatible.pdf");val before=hash(source);var checks=0
        try{FormPdfEngine.inspect(source,scratch()){if(++checks==4)throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){assertEquals(before,hash(source))}
        val m=FormPdfEngine.inspect(source,scratch());val review=FormReview.create(m,values("filled"))!!;checks=0
        try{FormPdfEngine.prepare(source,File(dir,"cancel.pdf"),scratch(),m,review){if(++checks==10)throw kotlinx.coroutines.CancellationException()};fail()}catch(_:kotlinx.coroutines.CancellationException){assertEquals(before,hash(source))}
    }
}
