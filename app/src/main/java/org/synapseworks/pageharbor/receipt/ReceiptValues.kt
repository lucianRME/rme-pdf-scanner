package org.synapseworks.pageharbor.receipt

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

internal enum class ReceiptField(val label: String) {
    MERCHANT("Merchant"), DATE("Transaction date"), CURRENCY("Currency"), SUBTOTAL("Subtotal"),
    TAX("Tax"), TOTAL("Total"), TENDER("Tender"), CHANGE("Change"), DISCOUNT("Discount"), REFUND("Refund");
    val monetary get() = ordinal >= SUBTOTAL.ordinal
}
internal enum class ReceiptDateOrder { AUTO, DMY, MDY }
internal enum class ReceiptDecimal { AUTO, DOT, COMMA }
internal data class ReceiptRules(val dates: ReceiptDateOrder = ReceiptDateOrder.AUTO, val decimals: ReceiptDecimal = ReceiptDecimal.AUTO)

/** Strict finite formats; no locale, Double, rounding, or inferred missing fields. */
internal object ReceiptValues {
    val currencies = setOf("EUR", "USD", "GBP", "CAD", "AUD", "RON", "CHF")
    fun safe(value: String): Boolean = org.synapseworks.pageharbor.businesscard.safeContactValue(value,256)
    fun currency(value: String): String? = when(val s = value.trim().uppercase(Locale.ROOT)) {
        "€" -> "EUR"; "£" -> "GBP"; "US$" -> "USD"; "CA$" -> "CAD"; "AU$" -> "AUD"
        else -> s.takeIf { it in currencies }
    }
    fun amount(value: String, rule: ReceiptDecimal = ReceiptDecimal.AUTO): String? {
        var s = value.trim()
        if(!safe(s) || s.isEmpty()) return null
        val parenthesized = s.startsWith('(') && s.endsWith(')')
        if(parenthesized) s = s.substring(1, s.length-1)
        if(!Regex("[+-]?[0-9][0-9.,]*").matches(s) || (parenthesized && (s.startsWith('-') || s.startsWith('+')))) return null
        val sign = if(parenthesized || s.startsWith('-')) "-" else ""
        s = s.removePrefix("-").removePrefix("+")
        val dot = s.lastIndexOf('.'); val comma = s.lastIndexOf(',')
        val decimal: Char? = when(rule) {
            ReceiptDecimal.DOT -> '.'; ReceiptDecimal.COMMA -> ','
            ReceiptDecimal.AUTO -> if(dot < 0 && comma < 0) null else if(dot > comma) '.' else ','
        }
        val parts = if(decimal != null) s.split(decimal) else listOf(s)
        if(parts.size > 2) return null
        val integer = parts[0]; val fraction = parts.getOrNull(1)
        if(fraction != null && (fraction.length != 2 || !fraction.all { it in '0'..'9' })) return null
        val group = if(decimal == ',') '.' else ','
        if(integer.any { it !in '0'..'9' && it != group }) return null
        if(group in integer && !Regex("[0-9]{1,3}(\\${group}[0-9]{3})+").matches(integer)) return null
        // AUTO does not interpret a lone three-digit group as a proven thousands mark.
        val digits = integer.replace(group.toString(), "")
        if(digits.length !in 1..9) return null
        return try { BigDecimal(sign + digits + "." + (fraction ?: "00")).setScale(2, RoundingMode.UNNECESSARY).toPlainString() }
        catch(_: NumberFormatException) { null }
    }
    fun date(value: String, order: ReceiptDateOrder = ReceiptDateOrder.AUTO): String? {
        val s = value.trim()
        return try {
            if(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(s)) LocalDate.parse(s).takeIf { it.year in 1..9999 }?.toString()
            else {
                val m = Regex("([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})").matchEntire(s) ?: return null
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
                if(y !in 1..9999) return null
                val dmy = when(order) { ReceiptDateOrder.DMY -> true; ReceiptDateOrder.MDY -> false
                    ReceiptDateOrder.AUTO -> when { a > 12 || a == b -> true; b > 12 -> false; else -> return null } }
                LocalDate.of(y, if(dmy) b else a, if(dmy) a else b).toString()
            }
        } catch(_: java.time.DateTimeException) { null }
    }
    fun normalize(field: ReceiptField, value: String, rules: ReceiptRules): String? {
        val s = value.trim(); if(!safe(s) || s.isBlank()) return null
        return when(field) {
            ReceiptField.MERCHANT -> s
            ReceiptField.DATE -> date(s, rules.dates)
            ReceiptField.CURRENCY -> currency(s)
            else -> amount(s, rules.decimals)
        }
    }
    fun warnings(values: Map<ReceiptField, String>): List<String> {
        fun n(f: ReceiptField) = values[f]?.let(::BigDecimal)
        val result = mutableListOf<String>()
        val subtotal = n(ReceiptField.SUBTOTAL); val tax = n(ReceiptField.TAX); val total = n(ReceiptField.TOTAL)
        val discount = n(ReceiptField.DISCOUNT)
        if(subtotal != null && tax != null && total != null) {
            if(discount != null && discount.signum() > 0) result += "Discount sign needs review; no subtraction was inferred."
            else if((subtotal + tax + (discount ?: BigDecimal.ZERO)).compareTo(total) != 0)
                result += "Subtotal + tax + signed discount differs from printed total. Amounts were preserved."
        }
        val tender = n(ReceiptField.TENDER); val change = n(ReceiptField.CHANGE)
        if(tender != null && change != null && total != null && (tender - change).compareTo(total) != 0)
            result += "Tender minus change differs from printed total. Amounts were preserved."
        return result
    }
}

internal data class ReceiptDraft(val values: Map<ReceiptField, String> = emptyMap(), val corrected: Set<ReceiptField> = emptySet()) {
    fun edit(field: ReceiptField, value: String) = if(value.length <= 256) copy(values = values + (field to value), corrected = corrected + field) else this
    fun normalized(rules: ReceiptRules): Map<ReceiptField, String>? {
        val result = mutableMapOf<ReceiptField, String>()
        for((f,v) in values) if(v.isNotBlank()) result[f] = ReceiptValues.normalize(f,v,rules) ?: return null
        return result
    }
    override fun toString() = "ReceiptDraft(<redacted>)"
}
