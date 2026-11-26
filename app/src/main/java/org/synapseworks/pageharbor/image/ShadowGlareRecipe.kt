package org.synapseworks.pageharbor.image

internal val DocumentFilter.shadowGlareIntensity: ShadowGlareIntensity?
    get() = when(this) {
        DocumentFilter.SHADOW_GLARE_LOW_V1 -> ShadowGlareIntensity.LOW
        DocumentFilter.SHADOW_GLARE_BALANCED_V1 -> ShadowGlareIntensity.BALANCED
        DocumentFilter.SHADOW_GLARE_STRONG_V1 -> ShadowGlareIntensity.STRONG
        else -> null
    }
