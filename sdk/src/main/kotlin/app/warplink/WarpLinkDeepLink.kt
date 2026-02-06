package app.warplink

data class WarpLinkDeepLink(
    val linkId: String,
    val destination: String,
    val deepLinkUrl: String? = null,
    val customParams: Map<String, Any> = emptyMap(),
    val isDeferred: Boolean = false,
    val matchType: MatchType? = null,
    val matchConfidence: Double? = null
)
