package app.warplink.internal.referral

import app.warplink.internal.AttributionResponse

/**
 * Remembers what a later claim needs from a match answer: the install the
 * server recorded and, when the match rested on a recovery token, the token.
 *
 * Neither is shown to the host. A token is kept only when the answer matched,
 * since a token that matched nothing proves nothing to a claim.
 */
internal fun recordReferralEvidence(
    stores: ReferralStores,
    response: AttributionResponse,
    invitation: ReferralInvitation? = null,
) {
    stores.evidence.rememberInstall(response.installId)
    if (invitation != null && response.matched) {
        stores.evidence.rememberToken(invitation.token, invitation.expiresAtSeconds)
    }
}
