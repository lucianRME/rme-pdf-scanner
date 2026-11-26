package org.synapseworks.pageharbor.image

internal enum class WhiteningPaper { NEUTRAL, COLOUR }
internal data class BackgroundWhiteningRecipe(val paper:WhiteningPaper,val amount:Float)

internal val DocumentFilter.whiteningRecipe:BackgroundWhiteningRecipe?
    get() = when(this) {
        DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.NEUTRAL,0.35f)
        DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.NEUTRAL,0.65f)
        DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.NEUTRAL,1f)
        DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.COLOUR,0.35f)
        DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.COLOUR,0.65f)
        DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1 -> BackgroundWhiteningRecipe(WhiteningPaper.COLOUR,1f)
        else -> null
    }
