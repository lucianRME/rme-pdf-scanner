package org.synapseworks.pageharbor.receipt

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.ocr.*

class ReceiptParserTest {
    @Test fun frozenIndependentCasesMatchEveryPresentAndMissingField() {
        val lines = javaClass.classLoader!!.getResourceAsStream("m21/parser-cases.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(15,lines.size)
        for(line in lines) {
            val (id,json,expectedJson) = line.split('\t')
            val source=json.removeSurrounding("\"").replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\")
            val expected=Regex("\"([A-Z]+)\": \"([^\"]*)\"").findAll(expectedJson).associate { ReceiptField.valueOf(it.groupValues[1]) to it.groupValues[2] }
            val result=ReceiptParser.parse(source)!!
            assertEquals(id,expected,result.draft().normalized(ReceiptRules()))
            assertEquals(source,result.source)
            result.fields.values.flatMap { it.candidates }.forEach { c ->
                assertTrue(c.rows.isNotEmpty()); c.rows.forEach { r -> assertTrue(r.indices.all { it in source.lines().indices }) }
            }
        }
    }
    @Test fun strictDecimalsHaveNoRoundingOrFloatingPoint() {
        val r=ReceiptValues
        assertEquals("999999999.99",r.amount("999,999,999.99"))
        assertEquals("1234.56",r.amount("1.234,56"))
        assertEquals("-5.20",r.amount("(5,20)")); assertEquals("0.00",r.amount("-0"))
        assertEquals("12.00",r.amount("12"))
        for(value in listOf("1,234","12.3","12.345","12.0000","2.OO","1e2","1000000000","1,23,456.78","NaN","--1","(-1.00)")) assertNull(r.amount(value))
        assertEquals("1234.00",r.amount("1,234",ReceiptDecimal.DOT))
        assertNull(r.amount("12.34",ReceiptDecimal.COMMA))
    }
    @Test fun strictDatesRequireExplicitOrderAndValidGregorianCalendar() {
        assertNull(ReceiptValues.date("03/04/2026"))
        assertEquals("2026-04-03",ReceiptValues.date("03/04/2026",ReceiptDateOrder.DMY))
        assertEquals("2026-03-04",ReceiptValues.date("03/04/2026",ReceiptDateOrder.MDY))
        assertEquals("2026-04-04",ReceiptValues.date("04/04/2026"))
        for(s in listOf("2026-02-29","31/04/2026","10/24/26","0000-01-01","2026-1-1")) assertNull(ReceiptValues.date(s))
        assertEquals("2024-02-29",ReceiptValues.date("2024-02-29"))
    }
    @Test fun explicitCurrencyDisambiguatesDollarButConflictingCodesRemainUncertain() {
        for((text,expected) in listOf("Total US$12.00" to "USD","Total €12.00" to "EUR","USD\nTotal $12.00" to "USD","Total $12.00" to null,"EUR\nUSD\nTotal 12.00" to null)) {
            val r=ReceiptParser.parse(text)!!; assertEquals(expected,r.draft().normalized(ReceiptRules())!![ReceiptField.CURRENCY])
        }
        assertNull(ReceiptValues.currency("JPY")); assertEquals("RON",ReceiptValues.currency("ron"))
    }
    @Test fun geometryJoinsOnlyAlignedNonOverlappingLabelAndAmountAndRetainsOriginalIndices() {
        val layout=OcrPageLayout(1000,1000,lines=listOf(
            OcrTextLine("12.00",OcrTextBounds(700f,200f,900f,240f)),
            OcrTextLine("Total",OcrTextBounds(20f,202f,200f,242f)),
            OcrTextLine("Cash",OcrTextBounds(20f,350f,200f,390f)),
            OcrTextLine("20.00",OcrTextBounds(700f,430f,900f,470f))))
        val r=ReceiptParser.parse("Total\nCash\n12.00\n20.00",layout)!!
        assertEquals("12.00",r.draft().values[ReceiptField.TOTAL]); assertNull(r.draft().values[ReceiptField.TENDER])
        assertEquals(listOf(1,0),r.fields.getValue(ReceiptField.TOTAL).candidates.single().rows.single().indices)
    }
    @Test fun arithmeticWarnsWithoutChangingPrintedValuesOrInventingTax() {
        val r=ReceiptParser.parse("Subtotal 0.10\nTax 0.20\nTotal 0.30")!!.draft().normalized(ReceiptRules())!!
        assertTrue(ReceiptValues.warnings(r).isEmpty())
        val bad=ReceiptParser.parse("Subtotal 10.00\nTax 2.00\nTotal 13.00")!!.draft().normalized(ReceiptRules())!!
        assertEquals("13.00",bad[ReceiptField.TOTAL]); assertEquals(1,ReceiptValues.warnings(bad).size)
        val sparse=ReceiptParser.parse("Subtotal 10.00\nTotal 12.00")!!.draft().normalized(ReceiptRules())!!
        assertFalse(sparse.containsKey(ReceiptField.TAX)); assertTrue(ReceiptValues.warnings(sparse).isEmpty())
        val discounts=mapOf(ReceiptField.SUBTOTAL to "20.00",ReceiptField.TAX to "1.80",ReceiptField.DISCOUNT to "-2.00",ReceiptField.TOTAL to "19.80")
        assertTrue(ReceiptValues.warnings(discounts).isEmpty())
        assertTrue(ReceiptValues.warnings(discounts+(ReceiptField.DISCOUNT to "2.00")).isNotEmpty())
    }
    @Test fun capsRejectOversizeAndCandidateOverflowCannotAssertLastOrFirstTotal() {
        assertNull(ReceiptParser.parse("x".repeat(32769)))
        assertNull(ReceiptParser.parse((1..257).joinToString("\n") { "a" }))
        assertNull(ReceiptParser.parse("a".repeat(1025)))
        val r=ReceiptParser.parse((1..17).joinToString("\n") { "Total 12.00" })!!
        assertEquals(16,r.fields.getValue(ReceiptField.TOTAL).candidates.size); assertNull(r.draft().values[ReceiptField.TOTAL])
        var checked=0
        try { ReceiptParser.parse("Total 12.00",check={checked++;throw java.util.concurrent.CancellationException()}); fail() }
        catch(_:java.util.concurrent.CancellationException) { assertTrue(checked>0) }
    }
    @Test fun controlsMalformedSurrogatesAndUnsafeMerchantNeverBecomeReviewedValues() {
        for(s in listOf("bad\u202Etext","bad\ntext","bad\u0000text","bad\uD800text")) {
            assertFalse(ReceiptValues.safe(s)); assertNull(ReceiptDraft().edit(ReceiptField.MERCHANT,s).normalized(ReceiptRules()))
        }
        val d=ReceiptDraft().edit(ReceiptField.MERCHANT,"https://example.invalid")
        assertEquals("https://example.invalid",d.normalized(ReceiptRules())!![ReceiptField.MERCHANT])
        assertFalse(d.toString().contains("example"))
        assertFalse(ReceiptParser.parse("Merchant: private synthetic")!!.toString().contains("private"))
    }
}
