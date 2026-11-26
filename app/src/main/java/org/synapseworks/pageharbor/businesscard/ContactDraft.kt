package org.synapseworks.pageharbor.businesscard

internal enum class ContactField { NAME, ORGANIZATION, TITLE, PHONE, EMAIL, ADDRESS }

/** In-memory values only. Never include card text in diagnostics. */
internal data class ContactDraft(
    val name: String = "", val organization: String = "", val title: String = "",
    val phones: List<String> = listOf(""), val emails: List<String> = listOf(""),
    val addresses: List<String> = listOf(""),
) {
    fun values() = listOf(name, organization, title) + phones + emails + addresses
    fun valid(): Boolean = phones.size <= 8 && emails.size <= 8 && addresses.size <= 4 &&
        listOf(name, organization, title).all { safeContactValue(it, 256) } &&
        (phones + emails).all { safeContactValue(it, 1024) } &&
        addresses.all { safeContactValue(it, 2048, multiline = true) } &&
        values().sumOf { it.length } <= 24000 && values().any { it.isNotBlank() }
    fun cleaned() = copy(name = name.trim(), organization = organization.trim(), title = title.trim(),
        phones = phones.map(String::trim).filter(String::isNotEmpty),
        emails = emails.map(String::trim).filter(String::isNotEmpty),
        addresses = addresses.map(String::trim).filter(String::isNotEmpty))
    fun edit(field: ContactField, index: Int, value: String): ContactDraft {
        val limit = when(field) { ContactField.ADDRESS -> 2048; ContactField.PHONE, ContactField.EMAIL -> 1024; else -> 256 }
        if (value.length > limit) return this
        fun update(list: List<String>) = list.mapIndexed { i, old -> if(i == index) value else old }
        return when(field) {
            ContactField.NAME -> copy(name = value)
            ContactField.ORGANIZATION -> copy(organization = value)
            ContactField.TITLE -> copy(title = value)
            ContactField.PHONE -> copy(phones = update(phones))
            ContactField.EMAIL -> copy(emails = update(emails))
            ContactField.ADDRESS -> copy(addresses = update(addresses))
        }
    }
    fun add(field: ContactField): ContactDraft = when(field) {
        ContactField.PHONE -> if(phones.size < 8) copy(phones = phones + "") else this
        ContactField.EMAIL -> if(emails.size < 8) copy(emails = emails + "") else this
        ContactField.ADDRESS -> if(addresses.size < 4) copy(addresses = addresses + "") else this
        else -> this
    }
    fun remove(field: ContactField, index: Int): ContactDraft {
        fun drop(list: List<String>) = list.filterIndexed { i, _ -> i != index }
        return when(field) {
            ContactField.PHONE -> copy(phones = drop(phones))
            ContactField.EMAIL -> copy(emails = drop(emails))
            ContactField.ADDRESS -> copy(addresses = drop(addresses))
            else -> this
        }
    }
    override fun toString() = "ContactDraft(<redacted>)"
}

internal fun safeContactValue(text: String, max: Int, multiline: Boolean = false): Boolean {
    if(text.length > max) return false
    var i = 0
    while(i < text.length) {
        val c = text[i++]
        val cp = if(Character.isHighSurrogate(c)) {
            if(i >= text.length || !Character.isLowSurrogate(text[i])) return false
            Character.toCodePoint(c, text[i++])
        } else { if(Character.isLowSurrogate(c)) return false; c.code }
        if((Character.isISOControl(cp) && !(multiline && cp == 10)) ||
            Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029) return false
    }
    return true
}

internal fun cardDisplay(text: String): String = buildString {
    text.codePoints().forEach { cp ->
        if((Character.isISOControl(cp) && cp != 10) || Character.getType(cp) == Character.FORMAT.toInt() ||
            cp == 0x2028 || cp == 0x2029 || cp in 0xd800..0xdfff)
            append("\\u{").append(cp.toString(16).uppercase(java.util.Locale.ROOT)).append('}')
        else appendCodePoint(cp)
    }
}
