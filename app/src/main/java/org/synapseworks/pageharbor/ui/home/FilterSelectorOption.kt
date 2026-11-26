package org.synapseworks.pageharbor.ui.home

import androidx.annotation.StringRes
import org.synapseworks.pageharbor.R
import org.synapseworks.pageharbor.image.DocumentFilter

internal data class FilterSelectorOption(
    val filter: DocumentFilter,
    @param:StringRes val labelRes: Int,
    @param:StringRes val contentDescriptionRes: Int,
)

internal val filterSelectorOptions = listOf(
    FilterSelectorOption(DocumentFilter.ORIGINAL, R.string.filter_original, R.string.filter_original),
    FilterSelectorOption(DocumentFilter.AUTO_ENHANCE, R.string.filter_enhance, R.string.filter_enhance),
    FilterSelectorOption(
        DocumentFilter.DOCUMENT_CLEANUP,
        R.string.filter_document_cleanup,
        R.string.filter_document_cleanup,
    ),
    FilterSelectorOption(DocumentFilter.GRAYSCALE, R.string.filter_grayscale, R.string.filter_grayscale),
    FilterSelectorOption(
        DocumentFilter.BLACK_AND_WHITE,
        R.string.filter_black_and_white_short,
        R.string.filter_black_and_white,
    ),
    FilterSelectorOption(
        DocumentFilter.HIGH_CONTRAST,
        R.string.filter_high_contrast,
        R.string.filter_high_contrast,
    ),
    FilterSelectorOption(DocumentFilter.SHADOW_GLARE_LOW_V1,R.string.filter_shadow_low,R.string.filter_shadow_low),
    FilterSelectorOption(DocumentFilter.SHADOW_GLARE_BALANCED_V1,R.string.filter_shadow_balanced,R.string.filter_shadow_balanced),
    FilterSelectorOption(DocumentFilter.SHADOW_GLARE_STRONG_V1,R.string.filter_shadow_strong,R.string.filter_shadow_strong),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_NEUTRAL_LOW_V1,R.string.filter_whitening_neutral_low,R.string.filter_whitening_neutral_low),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_NEUTRAL_BALANCED_V1,R.string.filter_whitening_neutral_balanced,R.string.filter_whitening_neutral_balanced),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_NEUTRAL_STRONG_V1,R.string.filter_whitening_neutral_strong,R.string.filter_whitening_neutral_strong),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_COLOUR_LOW_V1,R.string.filter_whitening_colour_low,R.string.filter_whitening_colour_low),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_COLOUR_BALANCED_V1,R.string.filter_whitening_colour_balanced,R.string.filter_whitening_colour_balanced),
    FilterSelectorOption(DocumentFilter.PAPER_WHITE_COLOUR_STRONG_V1,R.string.filter_whitening_colour_strong,R.string.filter_whitening_colour_strong),
    FilterSelectorOption(DocumentFilter.BLEED_THROUGH_LOW_V1,R.string.filter_bleed_low,R.string.filter_bleed_low),
    FilterSelectorOption(DocumentFilter.BLEED_THROUGH_BALANCED_V1,R.string.filter_bleed_balanced,R.string.filter_bleed_balanced),
    FilterSelectorOption(DocumentFilter.BLEED_THROUGH_STRONG_V1,R.string.filter_bleed_strong,R.string.filter_bleed_strong),
    FilterSelectorOption(DocumentFilter.CREASE_STAIN_LOW_V1,R.string.filter_crease_stain_low,R.string.filter_crease_stain_low),
    FilterSelectorOption(DocumentFilter.CREASE_STAIN_BALANCED_V1,R.string.filter_crease_stain_balanced,R.string.filter_crease_stain_balanced),
    FilterSelectorOption(DocumentFilter.CREASE_STAIN_STRONG_V1,R.string.filter_crease_stain_strong,R.string.filter_crease_stain_strong),
    FilterSelectorOption(DocumentFilter.MOIRE_LOW_V1,R.string.filter_moire_low,R.string.filter_moire_low),
    FilterSelectorOption(DocumentFilter.MOIRE_BALANCED_V1,R.string.filter_moire_balanced,R.string.filter_moire_balanced),
    FilterSelectorOption(DocumentFilter.MOIRE_STRONG_V1,R.string.filter_moire_strong,R.string.filter_moire_strong),
)
