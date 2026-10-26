package app.warplink

import app.warplink.referral.MatchMethod
import app.warplink.referral.ReferralClaimStatus
import app.warplink.referral.ReplayState

/**
 * The answer to [WarpLink.claimReferral].
 *
 * [ReferralClaimStatus.NONE] is an ordinary answer, not an error: the claim
 * found no referral to credit, and nothing was recorded for it.
 */
data class ReferralClaimResult(
    val status: ReferralClaimStatus,
    /** The id of the referral, or null when the status is none. */
    val referralId: String? = null,
    /** The link the referral came from, or null when the status is none. */
    val linkId: String? = null,
    /** The short URL of that link, or null when the status is none. */
    val linkUrl: String? = null,
    /** How the referral was matched, or null when the status is none. */
    val matchMethod: MatchMethod? = null,
    /** True when the match rests on exact evidence rather than an estimate. */
    val isExact: Boolean = false,
    /** The confidence of an estimated match, or null when there is none. */
    val matchConfidence: Double? = null,
    /** Reasons the referral was flagged, for example `outside_window`. */
    val flags: List<String> = emptyList(),
    /** For [ReferralClaimStatus.IGNORED], the referral that already holds the credit. */
    val duplicateOf: String? = null,
    /** True when this call replaced an earlier estimate with an exact match. */
    val updated: Boolean = false,
    val replayState: ReplayState? = null
)
