package org.synapseworks.pageharbor.forms

internal object FormLimits {
    const val INPUT=8*1024*1024;const val OUTPUT=16*1024*1024;const val SCRATCH=32L*1024*1024
    const val FIELDS=64;const val WIDGETS=128;const val OPTIONS=64;const val VALUE=1024;const val TOTAL=32768
    fun label(s:String,max:Int=240)=s.length<=max&&s.none{Character.isISOControl(it)||Character.getType(it)==Character.FORMAT.toInt()||Character.isSurrogate(it)}
    private val extra="€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ".toSet()
    fun text(s:String,multiline:Boolean)=s.length<=VALUE&&s.all{c->(multiline&&c in "\r\n")||(!Character.isISOControl(c)&&Character.getType(c)!=Character.FORMAT.toInt()&&(c in ' '..'ÿ'||c in extra))}
}
internal enum class FormKind { TEXT,CHECK,RADIO,CHOICE,MULTI }
internal data class FormOption(val value:String,val label:String){override fun toString()="FormOption(<redacted>)"}
internal data class FormField(val id:String,val label:String,val kind:FormKind,val initial:List<String>,val readOnly:Boolean,
    val required:Boolean,val pages:List<Int>,val options:List<FormOption> = emptyList(),val multiline:Boolean=false,val maxLength:Int=FormLimits.VALUE,val noToggleOff:Boolean=false,val flags:Int=0,val listBox:Boolean=false){
    override fun toString()="FormField(<redacted>)"
}
internal data class FormModel(val fields:List<FormField>,val pages:Int){
    fun initial()=fields.associate{it.id to it.initial.toList()}
    fun valid(values:Map<String,List<String>>):Boolean {
        if(values.keys!=fields.map{it.id}.toSet()||values.values.flatten().sumOf{it.length}>FormLimits.TOTAL)return false
        return fields.all{f->val v=values[f.id]?:return@all false
            if(f.readOnly&&v!=f.initial)return@all false
            when(f.kind){
                FormKind.TEXT->v.size==1&&v[0].length<=f.maxLength&&FormLimits.text(v[0],f.multiline)
                FormKind.CHECK->v.size==1&&(v[0]=="Off"||f.options.any{it.value==v[0]})
                FormKind.RADIO->v.size==1&&(v[0]=="Off"&&(!f.noToggleOff||f.initial==listOf("Off"))||f.options.any{it.value==v[0]})
                FormKind.CHOICE->v.size<=1&&v.all{x->f.options.any{it.value==x}}
                FormKind.MULTI->v.distinct().size==v.size&&v.size<=f.options.size&&v.all{x->f.options.any{it.value==x}}
            }
        }
    }
    override fun toString()="FormModel(<redacted>)"
}
internal class FormReview private constructor(val id:String,val values:Map<String,List<String>>){
    override fun toString()="FormReview(<redacted>)"
    companion object{fun create(model:FormModel,values:Map<String,List<String>>)=if(model.valid(values))FormReview(java.util.UUID.randomUUID().toString(),values.mapValues{it.value.toList()})else null}
}
internal enum class FormFailure(val message:String){
    MALFORMED("This PDF is malformed or its form structure is ambiguous. The original is unchanged."),
    UNSUPPORTED("This PDF uses unsupported fields, appearances or text. The original is unchanged."),
    ACTIVE("This PDF includes active, embedded, signed, restricted or other unsupported features. The original is unchanged."),
    ENCRYPTED("Encrypted PDFs are outside this form workflow. The original is unchanged."),
    LIMIT("This PDF exceeds the supported processing limits. The original is unchanged."),
    FIT("A value does not fit its field or uses unsupported characters. Return to edit and shorten it or add supported line breaks."),
    SOURCE("This provider cannot supply a local regular PDF. Choose an on-device file."),
    SOURCE_CHANGED("The original changed outside this workflow. Reopen it before saving a copy."),
    DESTINATION("Choose a new empty file in the system local Documents or Downloads provider. This destination cannot be used safely."),
    WRITE("The separate copy could not be saved. The destination may contain partial PDF; inspect it in Files before retrying.")
}
internal class FormException(val reason:FormFailure):java.io.IOException("Form operation declined")
internal class FormBudget(private val check:()->Unit={},private val clock:()->Long=System::nanoTime){
    private val deadline=clock()+15_000_000_000L;private var operations=0;private var bytes=0L;private var nodes=0
    fun step(read:Int=0){check();operations++;bytes+=read.coerceAtLeast(0);if(operations>200000||bytes>64L*1024*1024||clock()>deadline)throw FormException(FormFailure.LIMIT)}
    fun node(depth:Int){step();if(++nodes>50000||depth>32)throw FormException(FormFailure.LIMIT)}
}
