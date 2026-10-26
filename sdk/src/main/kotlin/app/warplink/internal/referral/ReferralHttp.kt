package app.warplink.internal.referral

import app.warplink.WarpLinkError

/**
 * Reading the answers of the two referral routes.
 *
 * Takes the pieces of a response rather than the connection, so the mapping is
 * testable without a socket.
 */
internal object ReferralHttp {

    const val TOKEN_EXPIRED_CODE = "RECOVERY_TOKEN_EXPIRED"
    const val TOKEN_INVALID_CODE = "RECOVERY_TOKEN_INVALID"
    const val TOKEN_PENDING_CODE = "RECOVERY_TOKEN_PENDING"

    /**
     * The failure for a response that is not a 200.
     *
     * A 400 that refuses the token carries the reopen-the-invitation message.
     * A 503 that says the token is not confirmed yet carries the try-again
     * message and is not a refusal, so the invitation is kept for a retry. Any
     * other status keeps what the server said, because the message is written
     * for the developer who called the route and holds no secret.
     *
     * @param retryAfterSeconds the parsed `Retry-After`, kept on the error.
     */
    fun failure(
        status: Int,
        code: String?,
        message: String?,
        retryAfterSeconds: Double?,
    ): WarpLinkError = when {
        status == 401 || status == 403 -> WarpLinkError.InvalidApiKey
        status == ReferralMessages.REFUSAL_STATUS -> refusal(code, message)
        status == ReferralMessages.PENDING_STATUS && code == TOKEN_PENDING_CODE ->
            WarpLinkError.ServerError(status, ReferralMessages.PENDING_INVITATION, retryAfterSeconds)
        else ->
            WarpLinkError.ServerError(status, message ?: "Server error", retryAfterSeconds)
    }

    private fun refusal(code: String?, message: String?): WarpLinkError {
        val text = when (code) {
            TOKEN_EXPIRED_CODE -> ReferralMessages.UNUSABLE_INVITATION
            TOKEN_INVALID_CODE -> ReferralMessages.INVALID_INVITATION
            else -> message ?: "Server error"
        }
        return WarpLinkError.ServerError(ReferralMessages.REFUSAL_STATUS, text)
    }
}
