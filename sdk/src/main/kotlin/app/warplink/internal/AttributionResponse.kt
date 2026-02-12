package app.warplink.internal

internal data class AttributionResponse(
    val matched: Boolean,
    val matchType: String?,
    val matchConfidence: Double?,
    val linkId: String?,
    val deepLinkUrl: String?,
    val destinationUrl: String?,
    val customParams: Map<String, Any>?,
    val installId: String?
)
