package app.warplink.internal.referral

import app.warplink.ReferralClaimResult
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError

/**
 * The facade's three referral calls, with the not-configured answer in one
 * place. A null [ReferralContext] means the SDK is not configured.
 */
internal object ReferralEntry {

    fun redeem(text: String, context: ReferralContext?, callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        if (context == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }
        ReferralRedeemer.redeem(text, context, callback)
    }

    fun claim(installerId: String, context: ReferralContext?, callback: (Result<ReferralClaimResult>) -> Unit) {
        if (context == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }
        ReferralClaimer.claim(installerId, context, callback)
    }

    /**
     * Finishes an invitation that a network failure left undelivered, and hands
     * a match to [deliver]. Only a match reaches it: no screen waits on a launch
     * resume, so a failure is logged and kept for the next launch.
     */
    fun resume(context: ReferralContext?, deliver: (Result<WarpLinkDeepLink?>) -> Unit) {
        if (context == null) return
        ReferralRedeemer.resume(context) { result ->
            if (result.getOrNull() != null) deliver(result)
        }
    }
}
