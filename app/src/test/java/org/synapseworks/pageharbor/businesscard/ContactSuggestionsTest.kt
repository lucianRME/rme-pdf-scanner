package org.synapseworks.pageharbor.businesscard

import org.junit.Assert.*
import org.junit.Test

class ContactSuggestionsTest {
    @Test fun labelledMultilineCardPreservesSpellingFormattingAndEveryValue() {
        val parsed = ContactSuggestionsParser.parse("""
            Name: Zoë O'Neill
            Company: Étude & Sons
            Title: R&D Lead
            Phone: +44 (0)20 7946 0100 ext. 12; +1 202-555-0100
            Email: Zoe.Oneill@example.invalid; sales@example.invalid
            Address: 12 Rue de l'Étude
            Suite 4

            Dublin D02 AB12
        """.trimIndent())!!
        assertEquals("Zoë O'Neill", parsed.draft.name)
        assertEquals("Étude & Sons", parsed.draft.organization)
        assertEquals("R&D Lead", parsed.draft.title)
        assertEquals(listOf("+44 (0)20 7946 0100 ext. 12", "+1 202-555-0100"), parsed.draft.phones)
        assertEquals(listOf("Zoe.Oneill@example.invalid", "sales@example.invalid"), parsed.draft.emails)
        assertEquals(listOf("12 Rue de l'Étude\nSuite 4\nDublin D02 AB12"), parsed.draft.addresses)
    }
    @Test fun ambiguousUnicodeLayoutDoesNotGuessNameCompanyTitleOrStreet() {
        val parsed = ContactSuggestionsParser.parse("山田 太郎\nAtelier Élan\nCreative Director\n12 Example Street\nhello@example.invalid")!!
        assertEquals("", parsed.draft.name); assertEquals("", parsed.draft.organization); assertEquals("", parsed.draft.title)
        assertEquals(listOf("山田 太郎", "Atelier Élan", "Creative Director", "12 Example Street"), parsed.candidates)
        assertEquals(listOf("hello@example.invalid"), parsed.draft.emails)
        assertEquals(listOf(""), parsed.draft.addresses)
    }
    @Test fun ocrErrorsAndSchemesStayDataAndMissingFieldsStayBlank() {
        val raw = "J0hn D0e\nTel: +44 2O 7946 O1OO\nEmail: john(at)example.invalid\njavascript:alert(1)\nhttps://example.invalid\nBEGIN:VCARD"
        val result = ContactSuggestionsParser.parse(raw)!!
        assertEquals(raw, result.source); assertEquals(listOf(""), result.draft.phones); assertEquals(listOf(""), result.draft.emails)
        assertTrue(result.candidates.contains("+44 2O 7946 O1OO")); assertTrue(result.candidates.contains("javascript:alert(1)"))
    }
    @Test fun deduplicationIsExactAndOverflowIsRejected() {
        val p = ContactSuggestionsParser.parse("Email: A@example.invalid; A@example.invalid; a@example.invalid")!!
        assertEquals(listOf("A@example.invalid", "a@example.invalid"), p.draft.emails)
        assertNull(ContactSuggestionsParser.parse("x".repeat(32769)))
        assertNull(ContactSuggestionsParser.parse((1..257).joinToString("\n") { "x" }))
        assertNull(ContactSuggestionsParser.parse((1..9).joinToString("\n") { "Phone: +1 202 555 010$it" }))
    }
    @Test fun draftCorrectionRemovalAndSafetyAreExplicit() {
        var draft = ContactDraft().edit(ContactField.NAME, 0, "  Åna Example  ")
            .edit(ContactField.PHONE, 0, "+353 (1) 234 5678 x42").add(ContactField.PHONE).edit(ContactField.PHONE, 1, "+44 20 7946 0100")
        assertTrue(draft.valid()); assertEquals("Åna Example", draft.cleaned().name)
        draft = draft.remove(ContactField.PHONE, 0); assertEquals(listOf("+44 20 7946 0100"), draft.phones)
        assertFalse(draft.copy(name = "bad\u202Ename").valid()); assertFalse(draft.copy(name = "bad\u0000name").valid())
        assertFalse(draft.copy(name = "\uD800").valid()); assertTrue(draft.copy(addresses = listOf("Line 1\nLine 2")).valid())
        assertFalse(ContactDraft().valid()); assertTrue(cardDisplay("bad\u202E\u0000").contains("\\u{202E}"))
        assertEquals("ContactDraft(<redacted>)", draft.toString())
    }
}
