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
     * to pick the destination to route to, not as a credential. A probabilistic
     * match comes from a hashed match key built from the IP address and
     * language, with time zone when available. Use it to route the user, not
     * to identify them. Authenticate the user and check authorization
     * separately before showing private data or doing anything sensitive,
     * guaranteed match or not.
     */
    val matchGuaranteed: Boolean = false
)
