package app.warplink.internal.referral

import app.warplink.internal.ApiClient
import app.warplink.internal.AttributionResponse
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.MatchSlot
import app.warplink.internal.matchWithRetry

/** What the recovery token in the Play referrer made of the first launch. */
internal sealed interface ReferrerTokenOutcome {

    /** The token matched. This answer is exact and ends the check. */
    data class Matched(val response: AttributionResponse) : ReferrerTokenOutcome

    /**
     * The token settled nothing: none was sent, it was refused, or it matched
     * nothing. The check goes on with the link id and the device signals.
     */
    data object FallThrough : ReferrerTokenOutcome

    /**
     * The token could not be settled now: the server cannot confirm it yet, or
     * the request failed. The check ends without completing, so the next launch
     * reads the same referrer and tries again. The token is kept, never
     * cleared, since nothing refused it.
     */
    data class Retry(val error: Throwable) : ReferrerTokenOutcome
}

/**
 * Redeems the recovery token the Play referrer carried, before any estimate.
 *
 * The request carries the token and nothing that could match on its own. The
 * server refuses a bad token only when no referrer or signals travel with it,
 * and a token it has not stored yet answers 503 for the same reason. Sent
 * alongside the device signals, either would fall through to an estimate and
 * the exact match would be lost for good, because a completed check never runs
 * again.
 */
internal fun redeemReferrerToken(
    token: String?,
    apiClient: ApiClient,
    storage: Storage,
    settings: RetrySettings,
    isCurrentCheck: () -> Boolean,
    deliver: (ReferrerTokenOutcome) -> Unit,
) {
    val invitation = token?.let(ReferralInvitation::parse)
    if (invitation == null || invitation.isExpired(storage.referral.nowSeconds())) {
        deliver(ReferrerTokenOutcome.FallThrough)
        return
    }
    matchWithRetry(
        apiClient, MatchSlot.DEFERRED_CHECK, settings, isCurrentCheck,
        signals = null,
        referrer = null,
        recoveryToken = invitation.token,
        tapId = storage.referral.referrerTap.tapIdFor(invitation.token),
        isReinstall = storage.isReinstall,
    ) { result ->
        deliver(
            synchronized(storage.stateLock) {
                if (isCurrentCheck()) outcomeOf(result, invitation, storage.referral)
                else ReferrerTokenOutcome.FallThrough
            }
        )
    }
}

private fun outcomeOf(
    result: Result<AttributionResponse>,
    invitation: ReferralInvitation,
    stores: ReferralStores,
): ReferrerTokenOutcome {
    val response = result.getOrNull()
    if (response != null) {
        if (!response.matched) return ReferrerTokenOutcome.FallThrough
        recordReferralEvidence(stores, response, invitation)
        return ReferrerTokenOutcome.Matched(response)
    }
    val error = result.exceptionOrNull() ?: return ReferrerTokenOutcome.FallThrough
    if (ReferralMessages.isInvitationRefusal(error)) {
        stores.evidence.clearToken(ifHolding = invitation.token)
        stores.referrerTap.clearIfHolding(invitation.token)
        return ReferrerTokenOutcome.FallThrough
    }
    return ReferrerTokenOutcome.Retry(error)
}
