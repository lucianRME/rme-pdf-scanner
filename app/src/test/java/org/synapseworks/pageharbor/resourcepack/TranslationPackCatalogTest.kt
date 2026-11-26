package org.synapseworks.pageharbor.resourcepack

import org.junit.Assert.*
import org.junit.Test
import org.synapseworks.pageharbor.translation.TranslationDirection
import org.synapseworks.pageharbor.translation.TranslationLanguage

class TranslationPackCatalogTest {
    @Test fun everyAdvertisedLanguageHasOnlySupportedDirectionsAndTheRequiredPack() {
        assertEquals(translationPackLanguages.size, translationPackLanguages.map { it.name }.toSet().size)
        val supportedNames = TranslationDirection.supported.flatMap { listOf(it.source, it.target) }
            .filter { it != TranslationLanguage.ENGLISH }.map { it.label }.toSet()
        assertEquals(supportedNames, translationPackLanguages.map { it.name }.toSet())
        translationPackLanguages.forEach { language ->
            val directions = TranslationDirection.supported.filter { it.source.label == language.name || it.target.label == language.name }
            assertTrue(directions.isNotEmpty())
            assertTrue(directions.all { direction ->
                when (language.pack) {
                    TranslationPack.ROMANIAN -> direction == TranslationDirection.EnglishToRomanian
                    TranslationPack.IRISH -> direction.usesIrishPack
                    TranslationPack.MULTILINGUAL -> direction.usesMultilingualPack
                }
            })
        }
    }
}
