package org.synapseworks.pageharbor.businesscard

internal val CONTACT_EMAIL_PATTERN = Regex("[\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~-]+@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}.-]*[\\p{L}\\p{N}])?\\.[\\p{L}]{2,}")

internal class ContactSuggestions(val draft: ContactDraft, val candidates: List<String>, val source: String) {
    override fun toString() = "ContactSuggestions(<redacted>)"
}

/** Conservative local suggestions, never a culturally speculative name/company classifier. */
internal object ContactSuggestionsParser {
    private val label = Regex("^(name|company|organization|organisation|title|job title|address|phone|tel|telephone|mobile|fax|email|e-mail)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val email = CONTACT_EMAIL_PATTERN
    private val phone = Regex("(?<![\\p{L}\\p{N}])(?:\\+?\\d|\\(\\d{1,5}\\))[\\d ()/.-]{5,}\\d(?:\\s*(?:ext\\.?|x|#)\\s*\\d{1,8})?", RegexOption.IGNORE_CASE)
    fun parse(raw: String, layout: org.synapseworks.pageharbor.ocr.OcrPageLayout? = null, check: () -> Unit = {}): ContactSuggestions? {
        if(raw.length > 32768) return null
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if(lines.size > 256) return null
        val recoveredEmails = ContactEmailLayoutRecovery.lines(lines, layout, check)
        var name = ""; var organization = ""; var title = ""
        val phones = mutableListOf<String>(); val emails = mutableListOf<String>(); val addresses = mutableListOf<String>(); val other = mutableListOf<String>()
        fun candidate(value: String) { if(value.isNotEmpty() && value.length <= 2048 && other.size < 32 && value !in other) other += value }
        var index = 0
        while(index < lines.size) {
            val line = lines[index++].trim(); if(line.isEmpty()) continue
            val matched = label.matchEntire(line)
            val kind = matched?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
            val value = matched?.groupValues?.get(2) ?: line
            when(kind) {
                "name" -> if(name.isEmpty() && safeContactValue(value, 256)) name = value else candidate(value)
                "company", "organization", "organisation" -> if(organization.isEmpty() && safeContactValue(value, 256)) organization = value else candidate(value)
                "title", "job title" -> if(title.isEmpty() && safeContactValue(value, 256)) title = value else candidate(value)
                "address" -> {
                    val block = mutableListOf(value)
                    while(index < lines.size && !label.matches(lines[index].trim()) &&
                        !email.containsMatchIn(recoveredEmails[lines[index].trim()] ?: lines[index]) && !phone.containsMatchIn(lines[index])) {
                        val continuation = lines[index++].trim(); if(continuation.isNotEmpty()) block += continuation
                    }
                    val address = block.joinToString("\n").trim()
                    if(addresses.size >= 4) return null
                    if(address.isNotEmpty() && safeContactValue(address, 2048, true)) addresses += address else candidate(address)
                }
                else -> {
                    val foundEmails = email.findAll(recoveredEmails[line] ?: value).map { it.value }.toList()
                    val foundPhones = phone.findAll(value).map { it.value.trim() }.filter { it.count(Char::isDigit) in 7..25 }.toList()
                    if((emails + foundEmails).distinct().size > 8 || (phones + foundPhones).distinct().size > 8) return null
                    for(e in foundEmails) if(emails.size < 8 && e !in emails && safeContactValue(e, 1024)) emails += e
                    for(p in foundPhones) if(phones.size < 8 && p !in phones && safeContactValue(p, 1024)) phones += p
                    // Unmatched text stays visible, including OCR errors and unsupported scripts/schemes.
                    val remainder = phone.replace(email.replace(value, ""), "").trim(' ', ';', ',', '|')
                    if(remainder.isNotEmpty()) candidate(remainder)
                }
            }
        }
        return ContactSuggestions(ContactDraft(name, organization, title, phones.ifEmpty { listOf("") },
            emails.ifEmpty { listOf("") }, addresses.ifEmpty { listOf("") }), other, raw)
    }
}
