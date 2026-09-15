package app.warplink

data class WarpLinkDeepLink(
    val linkId: String,
    val destination: String,
    val deepLinkUrl: String? = null,
    val customParams: Map<String, Any> = emptyMap(),
    val isDeferred: Boolean = false,
    val matchType: MatchType? = null,
    val matchConfidence: Double? = null,
    /**
     * True only when the match was deterministic. Gate anything sensitive
     * (auto sign-in, showing personal data) on this rather than on a confidence
     * threshold: a probabilistic match is a best guess from a network-shaped
     * fingerprint and can name the wrong user.
     */
    val matchGuaranteed: Boolean = false
)
