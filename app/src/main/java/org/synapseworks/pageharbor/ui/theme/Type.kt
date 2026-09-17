package org.synapseworks.pageharbor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A restrained system-font hierarchy shared by the library and document workflows. */
private val MaterialTypography = Typography()

val PageHarborTypography = MaterialTypography.copy(
    headlineSmall = MaterialTypography.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
    ),
    titleLarge = MaterialTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 30.sp,
    ),
    titleMedium = MaterialTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    ),
    bodyLarge = MaterialTypography.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = MaterialTypography.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = MaterialTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
)
