package org.synapseworks.pageharbor.library.smartnaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartNamingEngineTest {
    @Test
    fun receiptUsesTopMerchantAndMeaningfulTextualDate() {
        val suggestion = suggest("TESCO\nReceipt\n25 September 2026\nTotal €46.72")

        assertEquals("Tesco Receipt — 25 Sep 2026", suggestion?.name)
        assertEquals(SmartNameConfidence.HIGH, suggestion?.confidence)
        assertEquals(SmartDocumentType.RECEIPT, suggestion?.documentType)
    }

    @Test
    fun invoiceUsesOrganizationAndMonth() {
        assertEquals(
            "Vodafone Invoice — Sep 2026",
            suggest("Vodafone\nInvoice\nSeptember 2026")?.name,
        )
    }

    @Test
    fun strongMotorInsuranceSignalUsesConservativeSubtype() {
        assertEquals(
            "Allianz Car Insurance — 2026",
            suggest("Allianz\nMotor Insurance Certificate\n2026")?.name,
        )
    }

    @Test
    fun typeAndDateRemainUsefulWithoutOrganization() {
        assertEquals("Invoice — 25 Sep 2026", suggest("Invoice\n25 September 2026")?.name)
    }

    @Test
    fun organizationAndTypeRemainUsefulWithoutDate() {
        assertEquals("Vodafone Invoice", suggest("Vodafone Invoice")?.name)
    }

    @Test
    fun weakOrMissingOcrProducesNoSuggestion() {
        assertNull(suggest("Page 1\nThank you\nTotal 42.00"))
        assertNull(suggest(""))
    }

    @Test
    fun organizationRejectsContactAddressAndFinancialIdentifiers() {
        val suggestion = suggest(
            "Invoice\nVAT RO12345678\nhello@example.com\n+353 123 4567\n25 September 2026",
        )

        assertEquals("Invoice — 25 Sep 2026", suggestion?.name)
        assertNull(suggestion?.organization)
    }

    @Test
    fun commonUnambiguousDateFormatsAreLocaleSafe() {
        val cases = mapOf(
            "Invoice date: 2026-09-25" to "25 Sep 2026",
            "Invoice date: 25/09/2026" to "25 Sep 2026",
            "Invoice date: 25.09.2026" to "25 Sep 2026",
            "Invoice date: Sep 25, 2026" to "25 Sep 2026",
            "Invoice date: 25 September 2026" to "25 Sep 2026",
        )

        cases.forEach { (line, expected) ->
            assertEquals(expected, suggest("Invoice\n$line")?.date)
        }
    }

    @Test
    fun ambiguousDateIsOmittedInsteadOfGuessed() {
        assertNull(suggest("Invoice\n03/04/2026"))
    }

    @Test
    fun issueDateWinsAndDueDateIsIgnored() {
        assertEquals(
            "25 Sep 2026",
            suggest("Invoice\nDue date: 30 September 2026\nInvoice date: 25 September 2026")?.date,
        )
    }

    @Test
    fun multipleUnlabelledDatesAreOmittedAsAmbiguous() {
        val suggestion = suggest("Acme\nInvoice\n25 September 2026\n14 August 2025")

        assertEquals("Acme Invoice", suggestion?.name)
        assertNull(suggestion?.date)
    }

    @Test
    fun yearInsideOrganizationIsNotMisclassifiedAsDocumentDate() {
        val suggestion = suggest("2026 Holdings\nInvoice")

        assertEquals("2026 Holdings Invoice", suggestion?.name)
        assertNull(suggestion?.date)
    }

    @Test
    fun supportedTypeVocabularyStaysSmallAndDeterministic() {
        val cases = mapOf(
            "Invoice" to SmartDocumentType.INVOICE,
            "Receipt" to SmartDocumentType.RECEIPT,
            "Bank Statement" to SmartDocumentType.STATEMENT,
            "Insurance" to SmartDocumentType.INSURANCE,
            "Contract" to SmartDocumentType.CONTRACT,
            "Certificate" to SmartDocumentType.CERTIFICATE,
            "Letter" to SmartDocumentType.LETTER,
            "Form" to SmartDocumentType.FORM,
            "Report" to SmartDocumentType.REPORT,
            "Identity Card" to SmartDocumentType.IDENTITY_DOCUMENT,
        )

        cases.forEach { (text, expected) ->
            assertEquals(expected, suggest("Example Corp\n$text")?.documentType)
        }
    }

    @Test
    fun meaningfulManualTitleSuppressesSuggestionEvenForGenericImportName() {
        val suggestion = LocalSmartNamingEngine.suggest(
            SmartNamingInput(
                currentTitle = "Quarterly tax archive",
                importFileName = "IMG_1234.jpg",
                effectiveOcrPages = listOf("Vodafone\nInvoice\nSeptember 2026"),
            ),
        )

        assertNull(suggestion)
        assertFalse(isGenericDocumentTitle("Quarterly tax archive", "IMG_1234.jpg"))
        assertTrue(isGenericDocumentTitle("Imported.pdf"))
        assertTrue(isGenericDocumentTitle("Scan 2026-09-27"))
    }

    @Test
    fun unsafePunctuationWhitespaceAndRepeatedWordsAreNormalized() {
        val suggestion = suggest("ACME/ACME\nINVOICE INVOICE\n2026-09-25")

        assertTrue(requireNotNull(suggestion).name.length <= 120)
        assertFalse(suggestion.name.contains('/'))
        assertFalse(suggestion.name.contains("  "))
        assertFalse(suggestion.name.contains("Invoice Invoice"))
    }

    @Test
    fun longOcrIsBoundedAndDoesNotUseLateNoise() {
        val early = "Example Corp\nInvoice\n2026-09-25\n"
        val lateNoise = "x".repeat(13_000) + "\nWrong Company\nReceipt\n2027-01-01"

        assertEquals("Example Corp Invoice — 25 Sep 2026", suggest(early + lateNoise)?.name)
    }

    @Test
    fun westernEuropeanTypeLabelsPreserveDocumentLanguage() {
        val cases = mapOf(
            "Factură" to "Factură",
            "Rechnung" to "Rechnung",
            "Facture" to "Facture",
            "Fattura" to "Fattura",
            "Factura" to "Factura",
        )

        cases.forEach { (sourceType, expectedLabel) ->
            val suggestion = suggest("Exemplu SRL\n$sourceType\n25.09.2026")
            assertEquals(expectedLabel, suggestion?.name?.substringAfter("Exemplu SRL ")?.substringBefore(" —"))
        }
    }

    @Test
    fun nonLatinOrganizationsRemainUnicodeSafeWithoutTranslation() {
        val organizations = listOf("樱花株式会社", "株式会社サクラ", "한빛회사", "भारत संस्था")

        organizations.forEach { organization ->
            assertEquals(
                "$organization — 25 Sep 2026",
                suggest("$organization\n2026-09-25")?.name,
            )
        }
    }

    @Test
    fun repeatedOrganizationNameOutranksAWeakTopCandidate() {
        assertEquals(
            "Vodafone Invoice — 25 Sep 2026",
            suggest("Service summary\nVodafone\nInvoice\nVodafone\n25 September 2026")?.name,
        )
    }

    private fun suggest(text: String): SmartNameSuggestion? = LocalSmartNamingEngine.suggest(
        SmartNamingInput(
            currentTitle = "Document 12",
            effectiveOcrPages = listOf(text),
        ),
    )
}
