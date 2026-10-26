package app.warplink.internal.referral

import app.warplink.ReferralClaimResult
import app.warplink.WarpLinkError
import app.warplink.internal.BoundedRetry
import app.warplink.internal.onMainThread

/**
 * `WarpLink.claimReferral`: credits the referral behind this install to the
 * person who just signed up.
 *
 * Claiming is idempotent on the server for one installer and app, so the same
 * installer id may be sent again, and a retried request cannot credit twice.
 * `none` is an ordinary answer, not an error.
 */
internal object ReferralClaimer {

    const val MAX_INSTALLER_ID_LENGTH = 128

    fun claim(
        installerId: String,
        context: ReferralContext,
        callback: (Result<ReferralClaimResult>) -> Unit,
    ) {
        if (installerId.length !in 1..MAX_INSTALLER_ID_LENGTH) {
            callback(Result.failure(invalidInstallerId()))
            return
        }
        val stores = context.stores
        val evidence = ReferralClaimEvidence.current(stores.evidence, stores.nowSeconds())
        BoundedRetry(scheduler = context.apiClient, settings = context.settings).run<ReferralClaimResult>(
            attempt = { number, done ->
                context.apiClient.claimReferral(
                    installerId = installerId,
                    evidence = evidence,
                    timeoutMs = context.settings.timeoutMsFor(number),
                    onTokenRefused = { evidence.recoveryToken?.let { stores.evidence.clearToken(it) } },
                    callback = done,
                )
            },
            deliver = { result -> onMainThread { callback(result) } },
        )
    }

    private fun invalidInstallerId(): WarpLinkError =
        WarpLinkError.ServerError(
            400, "installerId must be 1 to $MAX_INSTALLER_ID_LENGTH characters"
        )
}
