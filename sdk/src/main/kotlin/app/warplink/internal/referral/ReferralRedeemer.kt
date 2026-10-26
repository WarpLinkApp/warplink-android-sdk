package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.internal.AttributionResponse
import app.warplink.internal.MatchSlot
import app.warplink.internal.matchWithRetry
import app.warplink.internal.routableDeepLink

/**
 * Redeeming an invitation the person pasted, and finishing one a network
 * failure left behind.
 *
 * The text arrives only through an explicit call. Nothing here reads the
 * clipboard.
 */
internal object ReferralRedeemer {

    /**
     * Redeems [text] when it holds a WarpLink invitation.
     *
     * Text that is not an invitation answers success(null) without a request:
     * the caller decides how to tell the person. An expired invitation answers
     * a failure without a request. Both leave onboarding free to go on.
     */
    fun redeem(text: String, context: ReferralContext, callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        val invitation = ReferralInvitation.parse(text)
        if (invitation == null) {
            context.logger?.log("Pasted text is not a WarpLink invitation, nothing sent")
            callback(Result.success(null))
            return
        }
        if (invitation.isExpired(context.stores.nowSeconds())) {
            callback(Result.failure(ReferralMessages.expiredError()))
            return
        }
        // A newer paste replaces an older record, and starts its own count. The
        // same invitation pasted again keeps its tap id, whether an earlier
        // paste or the Play referrer's redeem of it holds that id, so the server
        // still reads the person's own retry as the original.
        val tapId = context.stores.pending.load()?.takeIf { it.token == invitation.token }?.tapId
            ?: context.stores.referrerTap.heldTapId(invitation.token)
        val record = PendingRedemption(invitation.token, invitation.expiresAtSeconds)
            .let { if (tapId != null) it.copy(tapId = tapId) else it }
        if (!context.stores.beginRedeem()) {
            // Kept for the next launch: the run in flight must not be the last
            // word on an invitation the person has since replaced.
            context.stores.pending.save(record)
            context.logger?.log("A referral redeem is already running, kept this invitation for later")
            callback(Result.success(null))
            return
        }
        run(record, context, callback)
    }

    /**
     * Finishes the invitation an earlier run could not deliver, if one is held
     * and still worth sending. Answers nothing when there is none.
     */
    fun resume(context: ReferralContext, callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        val record = context.stores.pending.load() ?: return
        if (record.isExpired(context.stores.nowSeconds()) || !record.hasAttemptsLeft) {
            context.stores.pending.clear()
            return
        }
        if (!context.stores.beginRedeem()) return
        run(record, context, callback)
    }

    private fun run(
        held: PendingRedemption,
        context: ReferralContext,
        callback: (Result<WarpLinkDeepLink?>) -> Unit,
    ) {
        val record = held.copy(attempts = held.attempts + 1)
        // Written before the send, so a kill mid-request still counts the
        // attempt and the token survives for the next launch.
        context.stores.pending.save(record)
        matchWithRetry(
            context.apiClient, MatchSlot.REDEEM, context.settings, { true },
            signals = null,
            referrer = null,
            recoveryToken = record.token,
            tapId = record.tapId,
            isReinstall = context.storage.isReinstall,
        ) { result ->
            val outcome = finish(result, record, context)
            context.stores.endRedeem()
            callback(outcome)
        }
    }

    private fun finish(
        result: Result<AttributionResponse>,
        record: PendingRedemption,
        context: ReferralContext,
    ): Result<WarpLinkDeepLink?> {
        result.onSuccess { response ->
            context.stores.pending.clearIfHolding(record.token)
            return Result.success(apply(response, record, context))
        }
        val error = result.exceptionOrNull() ?: WarpLinkError.NetworkError(IllegalStateException("no result"))
        settleFailure(error, record, context)
        return Result.failure(error)
    }

    /**
     * A refusal ends the record: the same token would be refused again. Any
     * other failure, a 503 that says the token is not confirmed yet included,
     * keeps it for a later launch until it runs out of attempts or time.
     */
    private fun settleFailure(error: Throwable, record: PendingRedemption, context: ReferralContext) {
        val spent = !record.hasAttemptsLeft || record.isExpired(context.stores.nowSeconds())
        if (ReferralMessages.isInvitationRefusal(error) || spent) {
            context.stores.pending.clearIfHolding(record.token)
        }
        context.logger?.log("Referral redeem failed: ${error.message}")
    }

    private fun apply(
        response: AttributionResponse,
        record: PendingRedemption,
        context: ReferralContext,
    ): WarpLinkDeepLink? {
        val invitation = ReferralInvitation(record.token, record.expiresAtSeconds)
        recordReferralEvidence(context.stores, response, invitation)
        val matched = routableDeepLink(response)
        if (matched == null) {
            context.logger?.log("Invitation redeemed, no link to route to")
            return null
        }
        context.onMatch(matched)
        context.logger?.log("Invitation redeemed: ${matched.linkId}")
        return matched
    }
}
