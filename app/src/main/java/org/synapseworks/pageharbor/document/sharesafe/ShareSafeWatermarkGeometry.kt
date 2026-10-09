package org.synapseworks.pageharbor.document.sharesafe

data class ShareSafeWatermarkStamp(
    val centerX: Float,
    val centerY: Float,
    val rotationDegrees: Float,
    /** Text size as a fraction of the shorter page edge. */
    val textSizeFraction: Float,
    /** Maximum rendered line width as a fraction of the page width. */
    val maximumWidthFraction: Float,
) {
    init {
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(textSizeFraction in 0.01f..0.2f)
        require(maximumWidthFraction in 0.1f..1f)
    }
}
/** Shared normalized placement contract used by both the settings preview and PDF renderer. */
object ShareSafeWatermarkGeometry {
    fun stamps(placement: ShareSafeWatermarkPlacement): List<ShareSafeWatermarkStamp> =
        when (placement) {
            ShareSafeWatermarkPlacement.DIAGONAL -> listOf(
                ShareSafeWatermarkStamp(
                    centerX = 0.5f,
                    centerY = 0.5f,
                    rotationDegrees = -32f,
                    textSizeFraction = 0.055f,
                    maximumWidthFraction = 0.82f,
                ),
            )
            ShareSafeWatermarkPlacement.FOOTER -> listOf(
                ShareSafeWatermarkStamp(
                    centerX = 0.5f,
                    centerY = 0.94f,
                    rotationDegrees = 0f,
                    textSizeFraction = 0.032f,
                    maximumWidthFraction = 0.9f,
                ),
            )
            ShareSafeWatermarkPlacement.REPEATED -> listOf(
                0.25f to 0.25f,
                0.75f to 0.25f,
                0.5f to 0.5f,
                0.25f to 0.75f,
                0.75f to 0.75f,
            ).map { (x, y) ->
                ShareSafeWatermarkStamp(
                    centerX = x,
                    centerY = y,
                    rotationDegrees = -28f,
                    textSizeFraction = 0.035f,
                    maximumWidthFraction = 0.42f,
                )
            }
        }
}
