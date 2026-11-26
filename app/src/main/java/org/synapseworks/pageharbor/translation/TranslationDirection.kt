package org.synapseworks.pageharbor.translation

internal enum class TranslationLanguage(val label: String, val modelId: Int) {
    ENGLISH("English", 128022),
    IRISH("Irish", 128030),
    FRENCH("French", 128028),
    GERMAN("German", 128020),
    ITALIAN("Italian", 128045),
    SPANISH("Spanish", 128023),
    JAPANESE("Japanese", 128046),
    KOREAN("Korean", 128052),
    CHINESE("Chinese", 128102),
    HINDI("Hindi", 128036),
    MARATHI("Marathi", 128063),
    NEPALI("Nepali", 128066),
    ROMANIAN("Romanian", 128076),
}

internal data class TranslationDirection(
    val source: TranslationLanguage,
    val target: TranslationLanguage,
) {
    val label: String get() = "${source.label} → ${target.label}"
    val usesIrishPack: Boolean
        get() = source == TranslationLanguage.IRISH || target == TranslationLanguage.IRISH
    val usesMultilingualPack: Boolean get() = this != EnglishToRomanian && !usesIrishPack

    companion object {
        val EnglishToRomanian = TranslationDirection(TranslationLanguage.ENGLISH, TranslationLanguage.ROMANIAN)

        val supported = listOf(EnglishToRomanian) + TranslationLanguage.entries
            .filter { it != TranslationLanguage.ENGLISH && it != TranslationLanguage.ROMANIAN }
            .flatMap { language ->
                listOf(
                    TranslationDirection(TranslationLanguage.ENGLISH, language),
                    TranslationDirection(language, TranslationLanguage.ENGLISH),
                )
            }
    }
}
