package app.warplink.internal.referral

import app.warplink.WarpLinkError

/**
 * What the person is told when an invitation cannot be used, and how a refusal
 * from the server is told apart from a failure worth retrying.
 *
 * The refusal messages say how to get a usable invitation and that the app
 * keeps working without one. They ride on [WarpLinkError.ServerError] with
 * status 400, the status the server refuses a token with, so no new error
 * class is added.
 */
internal object ReferralMessages {

    const val REFUSAL_STATUS = 400
    const val PENDING_STATUS = 503

    const val INVALID_INVITATION =
        "This invitation is not valid. Open the invitation link again, copy the " +
            "invitation, and paste it here. You can also continue without it."

    const val EXPIRED_INVITATION =
        "This invitation has expired. Open the invitation link again to get a " +
            "fresh one, then paste it here. You can also continue without it."

    /**
     * For a refusal the server words as "expired". The same refusal covers a
     * link that was paused or ended, so this line does not claim an expiry.
     */
    const val UNUSABLE_INVITATION =
        "This invitation cannot be used right now. Open the invitation link " +
            "again to get a fresh one, then paste it here. You can also continue " +
            "without it."

    /**
     * For a 503 while the server cannot confirm a just-issued invitation yet.
     * Not a refusal: the invitation is kept and tried again.
     */
    const val PENDING_INVITATION =
        "We could not confirm this invitation yet. Try again in a minute."

    private val REFUSALS = setOf(INVALID_INVITATION, EXPIRED_INVITATION, UNUSABLE_INVITATION)

    /** The error for an invitation known to be expired before any request. */
    fun expiredError(): WarpLinkError =
        WarpLinkError.ServerError(REFUSAL_STATUS, EXPIRED_INVITATION)

    fun isInvitationRefusal(error: Throwable): Boolean =
        error is WarpLinkError.ServerError &&
            error.statusCode == REFUSAL_STATUS &&
            error.message in REFUSALS

    fun isPending(error: Throwable): Boolean =
        error is WarpLinkError.ServerError &&
            error.statusCode == PENDING_STATUS &&
            error.message == PENDING_INVITATION
}
