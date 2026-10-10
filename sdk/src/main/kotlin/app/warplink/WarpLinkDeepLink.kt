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
     * True only when the match was deterministic. Use it, like matchConfidence,
     * to pick the destination to route to, not as a credential: a probabilistic
     * match is a best guess from a network-shaped fingerprint and can name the
     * wrong user. Authenticate the user and check authorization separately
     * before showing private data or doing anything sensitive, guaranteed
     * match or not.
     */
    val matchGuaranteed: Boolean = false
)
