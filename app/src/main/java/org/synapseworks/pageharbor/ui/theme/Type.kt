package org.synapseworks.pageharbor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A restrained system-font hierarchy shared by the library and document workflows. */
private val MaterialTypography = Typography()

val PageHarborTypography = MaterialTypography.copy(
    headlineMedium = MaterialTypography.headlineMedium.copy(
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp,
    ),
    headlineSmall = MaterialTypography.headlineSmall.copy(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
    ),
    titleLarge = MaterialTypography.titleLarge.copy(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
    ),
    titleMedium = MaterialTypography.titleMedium.copy(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
    ),
    bodyLarge = MaterialTypography.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = MaterialTypography.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = MaterialTypography.labelLarge.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    labelMedium = MaterialTypography.labelMedium.copy(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
    labelSmall = MaterialTypography.labelSmall.copy(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
)
