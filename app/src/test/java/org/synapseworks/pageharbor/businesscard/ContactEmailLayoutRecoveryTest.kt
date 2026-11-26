package org.synapseworks.pageharbor.businesscard

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

class ContactEmailLayoutRecoveryTest {
    private fun layout(text: String, parts: List<String>, gap: Float = 8f, dy: Float = 0f): OcrPageLayout {
        var x = 20f
        val elements = parts.mapIndexed { i, part ->
            val top = 100f + if(i == parts.lastIndex) dy else 0f
            OcrTextElement(part, OcrTextBounds(x, top, x + part.length * 20f, top + 52f)).also { x += part.length * 20f + gap }
        }
        return OcrPageLayout(2800, 800, 0, listOf(OcrTextLine(text, OcrTextBounds(20f, 100f, x, 152f + dy.coerceAtLeast(0f)), elements = elements)))
    }
    private fun parse(parts: List<String>, gap: Float = 8f, dy: Float = 0f): ContactSuggestions {
        val raw = parts.joinToString(" ")
        return ContactSuggestionsParser.parse(raw, layout(raw, parts, gap, dy))!!
    }
    @Test fun closeSameRowFragmentsRecoverExactAddressAndKeepOriginalSource() {
        val parts = listOf("Email:", "ada@studio.", "example"); val result = parse(parts)
        assertEquals(listOf("ada@studio.example"), result.draft.emails)
        assertEquals(parts.joinToString(" "), result.source)
        assertTrue(result.candidates.contains("ada@studio. example"))
    }
    @Test fun frozenCardGeometryRecoversCharactersWithoutAnySpellingChange() {
        val text = "Email: jane@example. invalid"
        val elements = listOf(OcrTextElement("Email:", OcrTextBounds(74f,480f,212f,532f)), OcrTextElement("jane@example.", OcrTextBounds(228f,480f,592f,532f)), OcrTextElement("invalid", OcrTextBounds(600f,480f,746f,532f)))
        val geometry = OcrPageLayout(1600,800,0,listOf(OcrTextLine(text,OcrTextBounds(74f,480f,746f,532f),elements=elements)))
        assertEquals(listOf("jane@example.invalid"), ContactSuggestionsParser.parse(text, geometry)!!.draft.emails)
    }
    @Test fun textAloneDoesNotRemoveSpacesOrInventAnAddress() {
        val result = ContactSuggestionsParser.parse("Email: ada@studio. example")!!
        assertEquals(listOf(""), result.draft.emails); assertTrue(result.candidates.contains("ada@studio. example"))
    }
    @Test fun wideGapRemainsAvailableForManualReview() {
        val result = parse(listOf("Email:", "ada@studio.", "example"), gap=20f)
        assertEquals(listOf(""), result.draft.emails); assertTrue(result.candidates.isNotEmpty())
    }
    @Test fun closeWordsWithoutAnEmailPunctuationBoundaryAreNotJoined() {
        val result = parse(listOf("ada@stud", "io.example"))
        assertEquals(listOf(""), result.draft.emails); assertTrue(result.candidates.isNotEmpty())
    }
    @Test fun differentBaselinesCannotBeJoined() {
        assertEquals(listOf(""), parse(listOf("ada@studio.", "example"), dy=18f).draft.emails)
    }
    @Test fun separateLinesCannotBeJoinedEvenWhenTheirTextLooksLikeAnEmail() {
        val first = layout("ada@studio.",listOf("ada@studio.")).lines.single()
        val second = layout("example",listOf("example")).lines.single()
        val raw = "ada@studio.\nexample"
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,OcrPageLayout(2800,800,0,listOf(first,second)))!!.draft.emails)
    }
    @Test fun aCompleteEmailIsNeverExtendedByAnotherToken() {
        assertEquals(listOf("ada@studio.example"),parse(listOf("ada@studio.example", ".other")).draft.emails)
    }
    @Test fun multipleAddressesRecoverInOrderWithoutDuplicateRows() {
        val result = parse(listOf("Email:","one@lab.","example;","two@work.","example"))
        assertEquals(listOf("one@lab.example","two@work.example"),result.draft.emails)
    }
    @Test fun overlappingValidInterpretationsAreLeftUnresolved() {
        val result = parse(listOf("ada@studio.","example",".other"))
        assertEquals(listOf(""),result.draft.emails); assertTrue(result.candidates.isNotEmpty())
    }
    @Test fun noLettersOrDigitsAreCorrectedByTheJoin() {
        assertEquals(listOf("al1ce@studio.example"),parse(listOf("al1ce@studio.","example")).draft.emails)
    }
    @Test fun mismatchedOrDuplicateLayoutTextCannotAuthorizeAJoin() {
        val raw = "ada@studio. example"; val l = layout(raw,listOf("ada@studio.","example"))
        assertEquals(listOf(""),ContactSuggestionsParser.parse("other@studio. example",l)!!.draft.emails)
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw+"\n"+raw,l.copy(lines=l.lines+l.lines))!!.draft.emails)
    }
    @Test fun absentOrZeroBoundsCannotAuthorizeAJoin() {
        val raw="ada@studio. example"; val l=layout(raw,listOf("ada@studio.","example")); val line=l.lines.single()
        for(bounds in listOf(null,OcrTextBounds(20f,100f,40f,100f))) {
            val elements=line.elements.map { it.copy(bounds=bounds) }
            assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,l.copy(lines=listOf(line.copy(elements=elements))))!!.draft.emails)
        }
    }
    @Test fun layoutAndElementWorkLimitsFallBackToReview() {
        val raw="ada@studio. example"; val l=layout(raw,listOf("ada@studio.","example")); val line=l.lines.single()
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,l.copy(lines=List(257){line}))!!.draft.emails)
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,l.copy(lines=listOf(line.copy(elements=List(65){line.elements[0]}))))!!.draft.emails)
    }
    @Test fun freeformOrNonUprightLayoutCannotAuthorizeAJoin() {
        val raw="ada@studio. example"; val l=layout(raw,listOf("ada@studio.","example"))
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,l.copy(rotationDegrees=90))!!.draft.emails)
        assertEquals(listOf(""),ContactSuggestionsParser.parse(raw,l.copy(placementMode=OcrTextPlacementMode.FREEFORM_PAGE_REGION))!!.draft.emails)
    }
    @Test fun cancellationIsCheckedDuringRecovery() {
        val raw="ada@studio. example"; var checked=false
        try { ContactSuggestionsParser.parse(raw,layout(raw,listOf("ada@studio.","example"))) { checked=true; throw IllegalStateException("Synthetic cancellation") }; fail() }
        catch(_:IllegalStateException) { assertTrue(checked) }
    }
    @Test fun recoveredUnlabelledEmailEndsPostalContinuationAndWrongValuesStayEditable() {
        val line="ada@studio. example";val l=layout(line,listOf("ada@studio.","example"))
        val result=ContactSuggestionsParser.parse("Address: 12 Sample Road\n"+line,l)!!
        assertEquals(listOf("12 Sample Road"),result.draft.addresses);assertEquals(listOf("ada@studio.example"),result.draft.emails)
        val wrong=ContactSuggestionsParser.parse("Email: wrong@studio.example")!!.draft
        assertEquals(listOf("wrong@studio.example"),wrong.emails)
        assertEquals(listOf("reviewed@studio.example"),wrong.edit(ContactField.EMAIL,0,"reviewed@studio.example").emails)
    }
}
