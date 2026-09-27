package org.synapseworks.pageharbor.library.smartnaming

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.synapseworks.pageharbor.library.MAX_LIBRARY_TITLE_LENGTH

internal data class SmartNamingInput(
    val currentTitle: String,
    val effectiveOcrPages: List<String>,
    val importFileName: String? = null,
    val folderName: String? = null,
    val displayLocale: Locale = Locale.getDefault(),
)

data class SmartNameSuggestion(
    val name: String,
    val confidence: SmartNameConfidence,
    val organization: String?,
    val documentType: SmartDocumentType?,
    val date: String?,
)

enum class SmartNameConfidence { HIGH, MEDIUM }

enum class SmartDocumentType {
    INVOICE,
    RECEIPT,
    STATEMENT,
    INSURANCE,
    CONTRACT,
    CERTIFICATE,
    LETTER,
    FORM,
    REPORT,
    IDENTITY_DOCUMENT,
}

internal object LocalSmartNamingEngine {
    fun suggest(input: SmartNamingInput): SmartNameSuggestion? {
        if (!isGenericDocumentTitle(input.currentTitle, input.importFileName)) return null
        val lines = boundedLines(input.effectiveOcrPages)
        if (lines.isEmpty()) return null
        val joined = lines.joinToString("\n")
        val typeMatch = findDocumentType(joined)
        val date = findDocumentDate(lines, typeMatch != null, input.displayLocale)
        val organization = findOrganization(lines, typeMatch)
        val parts = listOfNotNull(organization, typeMatch?.label)
        val name = when {
            parts.isNotEmpty() && date != null -> "${parts.joinToString(" ")} — ${date.display}"
            parts.size == 2 -> parts.joinToString(" ")
            typeMatch != null && date != null -> "${typeMatch.label} — ${date.display}"
            organization != null && date != null -> "$organization — ${date.display}"
            else -> return null
        }.toSafeDocumentName()
        if (name.isBlank() || name.equals(input.currentTitle.trim(), ignoreCase = true)) return null
        val componentCount = listOfNotNull(organization, typeMatch, date).size
        return SmartNameSuggestion(
            name = name,
            confidence = if (componentCount == 3) SmartNameConfidence.HIGH else SmartNameConfidence.MEDIUM,
            organization = organization,
            documentType = typeMatch?.type,
            date = date?.display,
        )
    }
}

internal fun isGenericDocumentTitle(title: String, importFileName: String? = null): Boolean {
    val candidate = title.takeIf(String::isNotBlank) ?: importFileName.orEmpty()
    val normalized = candidate.substringBeforeLast('.', candidate)
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[_-]+"), " ")
        .replace(Regex("\\s+"), " ")
    return normalized.isBlank() || GENERIC_TITLE_PATTERNS.any { it.matches(normalized) }
}

private data class TypeTerm(val phrase: String, val label: String)

private data class TypeMatch(
    val type: SmartDocumentType,
    val label: String,
    val terms: List<TypeTerm>,
)

private data class TypeRule(val type: SmartDocumentType, val terms: List<TypeTerm>)

private fun findDocumentType(text: String): TypeMatch? {
    val normalized = text.lowercase(Locale.ROOT)
    TYPE_RULES.forEach { rule ->
        val matched = rule.terms.firstOrNull { term -> containsPhrase(normalized, term.phrase) }
        if (matched != null) return TypeMatch(rule.type, matched.label, rule.terms)
    }
    return null
}

private fun containsPhrase(text: String, phrase: String): Boolean = Regex(
    "(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])",
).containsMatchIn(text)

private fun boundedLines(pages: List<String>): List<String> {
    var remainingCharacters = MAX_SMART_NAMING_CHARACTERS
    val result = mutableListOf<String>()
    for (page in pages.take(MAX_SMART_NAMING_PAGES)) {
        if (remainingCharacters <= 0 || result.size >= MAX_SMART_NAMING_LINES) break
        for (rawLine in page.lineSequence()) {
            if (remainingCharacters <= 0 || result.size >= MAX_SMART_NAMING_LINES) break
            val boundedRawLine = rawLine.take(remainingCharacters)
            remainingCharacters -= boundedRawLine.length
            val line = boundedRawLine.replace(CONTROL_CHARACTERS, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_SMART_NAMING_LINE_LENGTH)
            if (line.isNotBlank()) {
                result += line
            }
            if (boundedRawLine.length < rawLine.length) break
        }
    }
    return result
}

private fun findOrganization(lines: List<String>, typeMatch: TypeMatch?): String? {
    val candidates = lines.take(MAX_ORGANIZATION_LINES).mapIndexedNotNull { index, line ->
        organizationCandidate(line, typeMatch)?.let { candidate -> index to candidate }
    }
    if (candidates.isEmpty()) return null
    val repetitions = candidates.groupingBy { it.second.lowercase(Locale.ROOT) }.eachCount()
    return candidates.maxByOrNull { (index, candidate) ->
        100 - (index * 4) + (repetitions[candidate.lowercase(Locale.ROOT)] ?: 0) * 25
    }?.second
}

private fun organizationCandidate(line: String, typeMatch: TypeMatch?): String? {
    var candidate = line.trim(' ', '-', '—', ':', '|')
    typeMatch?.terms?.forEach { term ->
        candidate = candidate.replace(
            Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(term.phrase)}(?![\\p{L}\\p{N}])"),
            " ",
        )
    }
    candidate = candidate.replace(DATE_LIKE_FRAGMENT, " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '—', ':', '|')
    if (candidate.length !in 2..60) return null
    val normalized = candidate.lowercase(Locale.ROOT)
    if (ORGANIZATION_REJECT_TERMS.any(normalized::contains)) return null
    if (candidate.contains('@') || URL_PATTERN.containsMatchIn(candidate)) return null
    if (PHONE_PATTERN.matches(candidate) || PAGE_HEADER_PATTERN.matches(candidate)) return null
    if (candidate.count(Char::isDigit) > candidate.count(Char::isLetter)) return null
    if (candidate.none(Char::isLetter)) return null
    if (candidate.split(Regex("\\s+")).size > 7) return null
    return humanizeOrganization(candidate.toSafeComponent())
        .takeIf { it.length >= 2 && it.any(Char::isLetter) }
}

private fun humanizeOrganization(value: String): String {
    if (value.none(Char::isLetter) || value.any(Char::isLowerCase)) return value
    return value.split(' ').joinToString(" ") { word ->
        if (word.count(Char::isLetter) <= 3) {
            word
        } else {
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}

private data class DateCandidate(
    val display: String,
    val valueKey: String,
    val score: Int,
    val ordinal: Int,
    val strongContext: Boolean,
)

private fun findDocumentDate(
    lines: List<String>,
    allowYearOnly: Boolean,
    displayLocale: Locale,
): DateCandidate? {
    val candidates = lines.take(MAX_DATE_LINES).flatMapIndexed { index, line ->
        dateCandidates(line, index, allowYearOnly, displayLocale)
    }.sortedWith(compareByDescending<DateCandidate>(DateCandidate::score).thenBy(DateCandidate::ordinal))
    val best = candidates.firstOrNull() ?: return null
    if (!best.strongContext && candidates.any { it.valueKey != best.valueKey }) return null
    return best
}

private fun dateCandidates(
    line: String,
    ordinal: Int,
    allowYearOnly: Boolean,
    displayLocale: Locale,
): List<DateCandidate> {
    val normalized = line.lowercase(Locale.ROOT)
    if (NEGATIVE_DATE_CONTEXT.any(normalized::contains)) return emptyList()
    val strongContext = POSITIVE_DATE_CONTEXT.any(normalized::contains)
    val contextScore = when {
        strongContext -> STRONG_DATE_CONTEXT_SCORE
        else -> 20 - ordinal.coerceAtMost(20)
    }
    val candidates = mutableListOf<DateCandidate>()
    ISO_DATE.findAll(line).forEach { match ->
        validDate(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let { date ->
            candidates += date.toCandidate(contextScore, ordinal, strongContext, displayLocale)
        }
    }
    NUMERIC_DATE.findAll(line).forEach { match ->
        val day = match.groupValues[1].toIntOrNull() ?: return@forEach
        val month = match.groupValues[2].toIntOrNull() ?: return@forEach
        val year = match.groupValues[3].toIntOrNull() ?: return@forEach
        if (day <= 12 && month <= 12 && day != month) return@forEach
        validDate(year, month, day)?.let { date ->
            candidates += date.toCandidate(contextScore, ordinal, strongContext, displayLocale)
        }
    }
    TEXTUAL_DAY_FIRST.findAll(normalized).forEach { match ->
        val month = MONTHS[match.groupValues[2]] ?: return@forEach
        validDate(
            match.groupValues[3].toIntOrNull() ?: return@forEach,
            month,
            match.groupValues[1].toIntOrNull() ?: return@forEach,
        )?.let { date ->
            candidates += date.toCandidate(contextScore, ordinal, strongContext, displayLocale)
        }
    }
    TEXTUAL_MONTH_FIRST.findAll(normalized).forEach { match ->
        val month = MONTHS[match.groupValues[1]] ?: return@forEach
        validDate(
            match.groupValues[3].toIntOrNull() ?: return@forEach,
            month,
            match.groupValues[2].toIntOrNull() ?: return@forEach,
        )?.let { date ->
            candidates += date.toCandidate(contextScore, ordinal, strongContext, displayLocale)
        }
    }
    if (candidates.isEmpty()) {
        TEXTUAL_MONTH_YEAR.findAll(normalized).forEach { match ->
            val month = MONTHS[match.groupValues[1]] ?: return@forEach
            val year = match.groupValues[2].toIntOrNull() ?: return@forEach
            runCatching { YearMonth.of(year, month) }.getOrNull()?.let { yearMonth ->
                candidates += DateCandidate(
                    display = yearMonth.format(monthYearFormat(displayLocale)),
                    valueKey = yearMonth.toString(),
                    score = contextScore - 2,
                    ordinal = ordinal,
                    strongContext = strongContext,
                )
            }
        }
    }
    val standaloneYear = YEAR_ONLY.replace(normalized, "")
        .replace(Regex("(?iu)\\b(?:date|data|datum|fecha)\\b"), "")
        .trim(' ', '.', ',', ':', '-', '—')
        .isBlank()
    if (
        allowYearOnly && candidates.isEmpty() && !NUMERIC_DATE.containsMatchIn(line) &&
        (contextScore >= STRONG_DATE_CONTEXT_SCORE || standaloneYear)
    ) {
        YEAR_ONLY.find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.let { year ->
            if (year in MIN_DOCUMENT_YEAR..MAX_DOCUMENT_YEAR) {
                candidates += DateCandidate(
                    year.toString(),
                    year.toString(),
                    contextScore - 5,
                    ordinal,
                    strongContext,
                )
            }
        }
    }
    return candidates.distinctBy(DateCandidate::valueKey)
}

private fun validDate(year: String, month: String, day: String): LocalDate? {
    return validDate(
        year.toIntOrNull() ?: return null,
        month.toIntOrNull() ?: return null,
        day.toIntOrNull() ?: return null,
    )
}

private fun validDate(year: Int, month: Int, day: Int): LocalDate? = try {
    if (year !in MIN_DOCUMENT_YEAR..MAX_DOCUMENT_YEAR) null else LocalDate.of(year, month, day)
} catch (_: DateTimeException) {
    null
}

private fun LocalDate.toCandidate(
    score: Int,
    ordinal: Int,
    strongContext: Boolean,
    displayLocale: Locale,
) = DateCandidate(
    display = format(fullDateFormat(displayLocale)),
    valueKey = toString(),
    score = score,
    ordinal = ordinal,
    strongContext = strongContext,
)

private fun String.toSafeComponent(): String = replace(ILLEGAL_FILENAME_CHARACTERS, " ")
    .replace(CONTROL_CHARACTERS, " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '.', '-', '—', ':')

private fun String.toSafeDocumentName(): String {
    val words = toSafeComponent().split(' ').filter(String::isNotBlank)
    val deduplicated = buildList<String> {
        words.forEach { word ->
            if (lastOrNull()?.equals(word, ignoreCase = true) != true) add(word)
        }
    }.joinToString(" ")
    if (deduplicated.length <= MAX_LIBRARY_TITLE_LENGTH) return deduplicated
    return deduplicated.take(MAX_LIBRARY_TITLE_LENGTH).trimEnd().substringBeforeLast(' ', "")
        .trimEnd(' ', '.', '-', '—', ':')
}

private val GENERIC_TITLE_PATTERNS = listOf(
    Regex("document(?: \\d+)?"),
    Regex("scan(?: \\d{4}(?: \\d{1,2}){0,2}| \\d+)?"),
    Regex("scanned document(?: \\d+)?"),
    Regex("imported(?: document)?"),
    Regex("img \\d+"),
    Regex("image \\d+"),
    Regex("photo \\d+"),
    Regex("untitled(?: document)?"),
)

private fun terms(label: String, vararg phrases: String) = phrases.map { TypeTerm(it, label) }

private val TYPE_RULES = listOf(
    TypeRule(
        SmartDocumentType.INSURANCE,
        terms("Car Insurance", "motor insurance", "car insurance") +
            terms("Asigurare auto", "asigurare auto") +
            terms("Kfz-Versicherung", "kfz-versicherung", "kraftfahrzeugversicherung") +
            terms("Assurance auto", "assurance auto") +
            terms("Assicurazione auto", "assicurazione auto") +
            terms("Seguro de coche", "seguro de coche", "seguro de automóvil"),
    ),
    TypeRule(
        SmartDocumentType.INVOICE,
        terms("Invoice", "invoice") + terms("Factură", "factură") + terms("Factura", "factura") +
            terms("Rechnung", "rechnung") + terms("Facture", "facture") +
            terms("Fattura", "fattura") + terms("Factura", "factura"),
    ),
    TypeRule(
        SmartDocumentType.RECEIPT,
        terms("Receipt", "receipt") + terms("Bon fiscal", "bon fiscal", "chitanță", "chitanta") +
            terms("Kassenbon", "kassenbon", "quittung") + terms("Reçu", "reçu", "ticket de caisse") +
            terms("Ricevuta", "ricevuta", "scontrino") + terms("Recibo", "recibo", "ticket de compra"),
    ),
    TypeRule(
        SmartDocumentType.STATEMENT,
        terms("Statement", "statement") + terms("Extras de cont", "extras de cont") +
            terms("Kontoauszug", "kontoauszug") + terms("Relevé", "relevé de compte") +
            terms("Estratto conto", "estratto conto") + terms("Extracto", "extracto bancario"),
    ),
    TypeRule(
        SmartDocumentType.INSURANCE,
        terms("Insurance", "insurance") + terms("Asigurare", "asigurare") +
            terms("Versicherung", "versicherung") + terms("Assurance", "assurance") +
            terms("Assicurazione", "assicurazione") + terms("Seguro", "seguro"),
    ),
    TypeRule(
        SmartDocumentType.CONTRACT,
        terms("Contract", "contract") + terms("Contract", "contract") +
            terms("Vertrag", "vertrag") + terms("Contrat", "contrat") +
            terms("Contratto", "contratto") + terms("Contrato", "contrato"),
    ),
    TypeRule(
        SmartDocumentType.CERTIFICATE,
        terms("Certificate", "certificate") + terms("Certificat", "certificat") +
            terms("Bescheinigung", "bescheinigung", "zertifikat") + terms("Certificat", "certificat") +
            terms("Certificato", "certificato") + terms("Certificado", "certificado"),
    ),
    TypeRule(
        SmartDocumentType.IDENTITY_DOCUMENT,
        terms("Identity Document", "identity card", "identity document") +
            terms("Carte de identitate", "carte de identitate") + terms("Personalausweis", "personalausweis") +
            terms("Carte d’identité", "carte d'identité", "carte d’identité") +
            terms("Carta d’identità", "carta d'identità", "carta d’identità") +
            terms("Documento de identidad", "documento nacional de identidad", "documento de identidad"),
    ),
    TypeRule(
        SmartDocumentType.LETTER,
        terms("Letter", "letter") + terms("Scrisoare", "scrisoare") + terms("Brief", "brief") +
            terms("Lettre", "lettre") + terms("Lettera", "lettera") + terms("Carta", "carta"),
    ),
    TypeRule(
        SmartDocumentType.FORM,
        terms("Form", "form") + terms("Formular", "formular") + terms("Formular", "formular") +
            terms("Formulaire", "formulaire") + terms("Modulo", "modulo") + terms("Formulario", "formulario"),
    ),
    TypeRule(
        SmartDocumentType.REPORT,
        terms("Report", "report") + terms("Raport", "raport") + terms("Bericht", "bericht") +
            terms("Rapport", "rapport") + terms("Relazione", "relazione") + terms("Informe", "informe"),
    ),
)

private val MONTHS = buildMap {
    fun month(number: Int, vararg names: String) = names.forEach { put(it, number) }
    month(1, "jan", "january", "ian", "ianuarie", "januar", "janvier", "gennaio", "enero")
    month(2, "feb", "february", "februarie", "februar", "février", "fevrier", "febbraio", "febrero")
    month(3, "mar", "march", "martie", "märz", "maerz", "mars", "marzo")
    month(4, "apr", "april", "aprilie", "avril", "aprile", "abril")
    month(5, "may", "mai", "maggio", "mayo")
    month(6, "jun", "june", "iunie", "juni", "juin", "giugno", "junio")
    month(7, "jul", "july", "iulie", "juli", "juillet", "luglio", "julio")
    month(8, "aug", "august", "august", "août", "aout", "agosto")
    month(9, "sep", "sept", "september", "septembrie", "septembre", "settembre", "septiembre")
    month(10, "oct", "october", "octombrie", "oktober", "octobre", "ottobre", "octubre")
    month(11, "nov", "november", "noiembrie", "novembre", "noviembre")
    month(12, "dec", "december", "decembrie", "dezember", "décembre", "decembre", "dicembre", "diciembre")
}

private val MONTH_PATTERN = MONTHS.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
private val TEXTUAL_DAY_FIRST = Regex("(?iu)(\\d{1,2})(?:st|nd|rd|th)?[\\s.,/-]+($MONTH_PATTERN)[\\s.,/-]+(\\d{4})")
private val TEXTUAL_MONTH_FIRST = Regex("(?iu)($MONTH_PATTERN)[\\s.,/-]+(\\d{1,2})(?:st|nd|rd|th)?[,]?[\\s]+(\\d{4})")
private val TEXTUAL_MONTH_YEAR = Regex("(?iu)(?<![\\p{L}])($MONTH_PATTERN)[\\s.,/-]+(\\d{4})(?!\\d)")
private val ISO_DATE = Regex("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
private val NUMERIC_DATE = Regex("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?!\\d)")
private val YEAR_ONLY = Regex("(?<!\\d)(20\\d{2})(?!\\d)")
private val DATE_LIKE_FRAGMENT = Regex(
    "(?iu)\\b(?:\\d{1,4}[./-]){1,2}\\d{1,4}\\b|\\b(?:$MONTH_PATTERN)(?:\\s+20\\d{2})?\\b",
)
private fun fullDateFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM uuuu", locale)

private fun monthYearFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM uuuu", locale)
private val ILLEGAL_FILENAME_CHARACTERS = Regex("[\\\\/:*?\"<>|]")
private val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]")
private val URL_PATTERN = Regex("(?iu)(?:https?://|www\\.)")
private val PHONE_PATTERN = Regex("^[+()\\d .-]{7,}$")
private val PAGE_HEADER_PATTERN = Regex("(?iu)^page\\s+\\d+(?:\\s+of\\s+\\d+)?$")

private val ORGANIZATION_REJECT_TERMS = listOf(
    "invoice number", "reference", "vat", "tax id", "cui", "iban", "swift", "total", "subtotal",
    "amount due", "due date", "payment", "thank you", "customer", "account number", "address",
    "street", "strada", "straße", "strasse", "rue ", "via ", "calle ", "postcode", "postal code",
)
private val POSITIVE_DATE_CONTEXT = listOf(
    "invoice date", "receipt date", "statement date", "issue date", "document date", "date issued",
    "data facturii", "data emiterii", "rechnungsdatum", "ausstellungsdatum", "date de facture",
    "date d’émission", "date d'emission", "data fattura", "data di emissione", "fecha de factura",
    "fecha de emisión", "fecha de emision",
)
private val NEGATIVE_DATE_CONTEXT = listOf(
    "due date", "payment due", "deadline", "pay by", "data scadentă", "data scadenta", "scadenza",
    "fällig", "faellig", "échéance", "echeance", "vencimiento", "date of birth", "birth date", "dob",
    "data nașterii", "data nasterii", "geburtsdatum", "date de naissance", "data di nascita", "fecha de nacimiento",
)

private const val MAX_SMART_NAMING_PAGES = 3
private const val MAX_SMART_NAMING_CHARACTERS = 12_000
private const val MAX_SMART_NAMING_LINES = 80
private const val MAX_SMART_NAMING_LINE_LENGTH = 240
private const val MAX_ORGANIZATION_LINES = 16
private const val MAX_DATE_LINES = 40
private const val STRONG_DATE_CONTEXT_SCORE = 100
private const val MIN_DOCUMENT_YEAR = 1900
private const val MAX_DOCUMENT_YEAR = 2100
