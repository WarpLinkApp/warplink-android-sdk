package app.warplink

import app.warplink.referral.EvidenceClass
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReplayState

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
     * match is an estimate from device signals, namely the IP address and
     * language, with time zone when available. Use it to route the user, not
     * to identify them. Authenticate the user and check authorization
     * separately before showing private data or doing anything sensitive,
     * guaranteed match or not.
     */
    val matchGuaranteed: Boolean = false,
    /**
     * The stable id of the attribution this link came from. It is the same on
     * every replay of the match. Null for a link that did not come from an
     * attribution, and for a payload cached before SDK 1.2.0.
     */
    val attributionId: String? = null,
    /** How the match was made. Null when the source is not known. */
    val matchMethod: MatchMethod? = null,
    /**
     * How strong the evidence is. Null when the source is not known, which is
     * the safe reading: never treat an unknown match as exact.
     */
    val evidenceClass: EvidenceClass? = null,
    /** Whether this is the first answer for the match or a repeat of it. */
    val replayState: ReplayState? = null,
    /** The link parameters the server captured at the tap. Empty when none. */
    val capturedParams: Map<String, Any> = emptyMap(),
    /**
     * When the link was tapped, as the ISO 8601 string the server sent. Null
     * when the server did not record a tap.
     */
    val tappedAt: String? = null
)
