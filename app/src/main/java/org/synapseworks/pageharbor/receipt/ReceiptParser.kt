package org.synapseworks.pageharbor.receipt

import org.synapseworks.pageharbor.ocr.*
import kotlin.math.abs

internal data class ReceiptRow(val indices: List<Int>, val text: String, val bounds: OcrTextBounds? = null) {
    override fun toString() = "ReceiptRow(<redacted>)"
}
internal data class ReceiptCandidate(val raw: String, val normalized: String?, val rows: List<ReceiptRow>) {
    override fun toString() = "ReceiptCandidate(<redacted>)"
}
internal data class ReceiptSuggestion(val value: String?, val candidates: List<ReceiptCandidate>, val status: String) {
    override fun toString() = "ReceiptSuggestion(<redacted>)"
}
internal data class ReceiptExtraction(val source: String, val rows: List<ReceiptRow>, val fields: Map<ReceiptField, ReceiptSuggestion>) {
    fun draft() = ReceiptDraft(fields.mapNotNull { (f,s) -> s.value?.let { f to it } }.toMap())
    override fun toString() = "ReceiptExtraction(<redacted>)"
}

internal object ReceiptParser {
    private val moneyLabel = Regex("^(sub\\s*total|grand\\s+total|total(?:\\s+due)?|amount\\s+due|tax|vat|cash|tender(?:ed)?|card|paid|change|discount|refund)\\b\\s*:?\\s*", RegexOption.IGNORE_CASE)
    private val merchantLabel = Regex("^(merchant|store)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val datePattern = Regex("(?<![0-9])(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4})(?![0-9])")
    private val currencyPattern = Regex("(?<![A-Za-z])(?:EUR|USD|GBP|CAD|AUD|RON|CHF|US\\$|CA\\$|AU\\$)(?![A-Za-z])|[€£$]")
    private val amountPattern = Regex("(?<![A-Za-z0-9.,])(?:\\([0-9][0-9.,]*\\)|[+-]?[0-9][0-9.,]*)(?![A-Za-z0-9.,%])")
    fun parse(text: String, layout: OcrPageLayout? = null, rules: ReceiptRules = ReceiptRules(), check: () -> Unit = {}): ReceiptExtraction? {
        if(text.length > 32768 || text.lineSequence().count() > 256) return null
        val lines = layout?.lines?.takeIf { it.isNotEmpty() } ?: text.lines().map(::OcrTextLine)
        if(lines.size > 256 || lines.sumOf { it.text.length } > 32768 || lines.any { it.text.length > 1024 }) return null
        return extract(text, rows(lines, check), rules, check)
    }
    /** Re-evaluate explicit formats without retaining the full OCR element graph. */
    fun reparse(source: ReceiptExtraction, rules: ReceiptRules) = extract(source.source, source.rows, rules) {}
    private fun rows(lines: List<OcrTextLine>, check: () -> Unit): List<ReceiptRow> {
        val result = mutableListOf<ReceiptRow>()
        val indexed = lines.mapIndexed { i,l -> ReceiptRow(listOf(i), l.text, l.bounds) }
        if(indexed.any { it.bounds == null }) return indexed
        for(row in indexed.sortedWith(compareBy<ReceiptRow> { it.bounds!!.top }.thenBy { it.bounds!!.left })) {
            check(); val b = row.bounds!!
            val index = result.indexOfLast { r -> val a = r.bounds!!
                (a.right <= b.left || b.right <= a.left) && abs((a.top+a.bottom-b.top-b.bottom)/2) <= minOf(a.bottom-a.top,b.bottom-b.top)*0.45f }
            if(index < 0) result += row else {
                val old = result[index]; val a = old.bounds!!
                val parts=if(a.left <= b.left) listOf(old,row) else listOf(row,old)
                result[index] = ReceiptRow(parts.flatMap { it.indices }, parts.joinToString(" ") { it.text }, OcrTextBounds(minOf(a.left,b.left),minOf(a.top,b.top),maxOf(a.right,b.right),maxOf(a.bottom,b.bottom)))
            }
        }
        return result
    }
    private fun extract(text: String, rows: List<ReceiptRow>, rules: ReceiptRules, check: () -> Unit): ReceiptExtraction {
        val candidates = ReceiptField.entries.associateWith { mutableListOf<ReceiptCandidate>() }
        val overflow = mutableSetOf<ReceiptField>()
        fun add(f: ReceiptField, raw: String, row: ReceiptRow) {
            val list = candidates.getValue(f)
            if(list.size >= 16) { overflow += f; return }
            list += ReceiptCandidate(raw, ReceiptValues.normalize(f,raw,rules), listOf(row))
        }
        var labelledMerchant = false
        for(row in rows) {
            check(); val s = row.text.trim()
            merchantLabel.matchEntire(s)?.let { labelledMerchant = true; add(ReceiptField.MERCHANT,it.groupValues[2],row) }
            // Transaction-labelled or standalone dates only; avoid expiry/order-number guesses.
            if(s.matches(Regex("(?i)^(?:transaction\\s+)?date\\s*:?.*")) || datePattern.matches(s))
                for(m in datePattern.findAll(s)) add(ReceiptField.DATE,m.value,row)
            for(m in currencyPattern.findAll(s)) add(ReceiptField.CURRENCY,m.value,row)
            val label = moneyLabel.find(s) ?: continue
            val f = when(label.groupValues[1].lowercase().replace(Regex("\\s+"),"")) {
                "subtotal" -> ReceiptField.SUBTOTAL; "tax","vat" -> ReceiptField.TAX
                "change" -> ReceiptField.CHANGE; "discount" -> ReceiptField.DISCOUNT; "refund" -> ReceiptField.REFUND
                "cash","tender","tendered","card","paid" -> ReceiptField.TENDER
                else -> ReceiptField.TOTAL
            }
            val rest = currencyPattern.replace(s.substring(label.range.last+1), "").trim()
            val matches = amountPattern.findAll(rest).toList()
            // A row containing competing amounts or an OCR-corrupted suffix stays uncertain.
            if(matches.size == 1) {
                val m = matches.single(); val remainder = rest.removeRange(m.range).trim()
                if(remainder.isEmpty() || remainder.matches(Regex("[0-9]+(?:[.,][0-9]+)?%"))) add(f,m.value,row)
                else add(f,rest,row)
            } else add(f,rest,row)
        }
        if(!labelledMerchant) {
            val first = rows.firstOrNull { it.text.isNotBlank() }
            first?.text?.trim()?.takeIf { s -> s.any(Char::isLetter) && s.none(Char::isDigit) &&
                !s.matches(Regex("(?i)receipt|invoice|thank you|tax receipt")) && currencyPattern.find(s)==null && moneyLabel.find(s)==null && !s.contains(':')
            }?.let { add(ReceiptField.MERCHANT,it,first) }
        }
        val currencyCandidates=candidates.getValue(ReceiptField.CURRENCY)
        val definite=currencyCandidates.mapNotNull { it.normalized }.distinct()
        if(definite.size==1 && definite.single() in setOf("USD","CAD","AUD")) {
            for(i in currencyCandidates.indices) if(currencyCandidates[i].raw=="$") currencyCandidates[i]=currencyCandidates[i].copy(normalized=definite.single())
        }
        val fields = candidates.mapValues { (f,list) ->
            val normalized = list.map { it.normalized }.distinct()
            val good = f !in overflow && normalized.size == 1 && normalized[0] != null
            ReceiptSuggestion(if(good) { if(f==ReceiptField.CURRENCY) normalized[0] else list.first().raw } else null, list,
                when { f in overflow -> "Too many candidates — review source"; list.isEmpty() -> "Missing — enter if known"
                    !good -> "Uncertain or conflicting — review source"
                    f==ReceiptField.MERCHANT && !labelledMerchant -> "Header suggestion — verify merchant"
                    else -> "Label/format suggestion — verify" })
        }
        return ReceiptExtraction(text,rows,fields)
    }
}
