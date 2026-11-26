package org.synapseworks.pageharbor.image

/** Non-destructive visual treatments for one active scan page. */
enum class DocumentFilter {
    ORIGINAL,
    AUTO_ENHANCE,
    GRAYSCALE,
    BLACK_AND_WHITE,
    HIGH_CONTRAST,
    /** Original cleanup algorithm v1; this persisted name must retain these processing semantics. */
    DOCUMENT_CLEANUP,
    /** Shadow/glare algorithm v1 at fixed intensity; persisted names must remain immutable. */
    SHADOW_GLARE_LOW_V1,
    SHADOW_GLARE_BALANCED_V1,
    SHADOW_GLARE_STRONG_V1,
    /** Background whitening v1. Colour-safe content guards; persisted recipes are immutable. */
    PAPER_WHITE_NEUTRAL_LOW_V1,
    PAPER_WHITE_NEUTRAL_BALANCED_V1,
    PAPER_WHITE_NEUTRAL_STRONG_V1,
    PAPER_WHITE_COLOUR_LOW_V1,
    PAPER_WHITE_COLOUR_BALANCED_V1,
    PAPER_WHITE_COLOUR_STRONG_V1,
    /** Conservative single-sided bleed-through v1; persisted semantics are immutable. */
    BLEED_THROUGH_LOW_V1,
    BLEED_THROUGH_BALANCED_V1,
    BLEED_THROUGH_STRONG_V1,
    /** Crease/stain correction v1; immutable observed-artifact processing, no reconstruction. */
    CREASE_STAIN_LOW_V1,
    CREASE_STAIN_BALANCED_V1,
    CREASE_STAIN_STRONG_V1,
    /** Finite achromatic harmonic rejection v1; persisted semantics are immutable. */
    MOIRE_LOW_V1,
    MOIRE_BALANCED_V1,
    MOIRE_STRONG_V1,
}
