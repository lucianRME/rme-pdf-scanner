package org.synapseworks.pageharbor.forms

import org.junit.Test
import org.junit.Assert.*

class FormModelsTest {
    private fun model()=FormModel(listOf(
        FormField("name","Name",FormKind.TEXT,listOf("0012"),false,false,listOf(1,2),maxLength=30),
        FormField("ref","Reference",FormKind.TEXT,listOf("KEEP 0O"),true,false,listOf(2)),
        FormField("check","Check",FormKind.CHECK,listOf("Off"),false,false,listOf(1),listOf(FormOption("Accepted","Accepted"))),
        FormField("radio","Radio",FormKind.RADIO,listOf("One"),false,false,listOf(1),listOf(FormOption("One","One"),FormOption("Two","Two")),noToggleOff=true),
        FormField("choice","Choice",FormKind.MULTI,listOf("a"),false,false,listOf(1),listOf(FormOption("a","Alpha"),FormOption("b","Beta")))
    ),2)
    @Test fun existingValuesAndIdentifiersStayLiteralAndReadonlyCannotChange(){val m=model();assertTrue(m.valid(m.initial()));assertFalse(m.valid(m.initial()+("ref" to listOf("changed"))));assertEquals("0012",m.initial()["name"]!!.single());assertFalse(m.toString().contains("KEEP"))}
    @Test fun optionsCardinalityAndNoToggleOffAreEnforced(){val m=model();val v=m.initial();assertFalse(m.valid(v+("radio" to listOf("Off"))));assertFalse(m.valid(v+("check" to listOf("Yes"))));assertFalse(m.valid(v+("choice" to listOf("a","a"))));assertFalse(m.valid(v+("choice" to listOf("c"))));assertTrue(m.valid(v+("choice" to listOf("a","b"))))}
    @Test fun unicodeWhitespaceLengthAndUnknownFieldsDoNotSilentlyNormalize(){val m=model();assertTrue(m.valid(m.initial()+("name" to listOf("Zoë €0010"))));for(s in listOf("😀","a\u202eb","a\u00adb","cafe\u0301","line\nnext","x".repeat(31))){assertFalse(m.valid(m.initial()+("name" to listOf(s))))};assertFalse(m.valid(m.initial()+("other" to listOf("x"))))}
    @Test fun reviewedValuesOwnAllMutableCallerLists(){val m=model();val values=m.initial().toMutableMap();val name=mutableListOf("Zoë €0010");values["name"]=name;val r=FormReview.create(m,values)!!;name[0]="changed";values.clear();assertEquals("Zoë €0010",r.values["name"]!!.single());assertFalse(r.toString().contains("Zoë"))}
    @Test fun checkedDeadlinesReadAndTraversalBudgetsFailClosed(){var time=0L;val b=FormBudget(clock={time});b.step();time=15_000_000_001;try{b.step();fail()}catch(e:FormException){assertEquals(FormFailure.LIMIT,e.reason)};try{FormBudget().step(65*1024*1024);fail()}catch(_:FormException){};try{FormBudget().node(33);fail()}catch(_:FormException){}}
    @Test fun cancellationIsCheckedAtEveryBudgetBoundary(){try{FormBudget({throw kotlinx.coroutines.CancellationException()}).step();fail()}catch(_:kotlinx.coroutines.CancellationException){}}
}
