package app.warplink.internal.referral

import app.warplink.ReferralClaimResult
import app.warplink.WarpLinkError
import app.warplink.internal.ApiClient
import app.warplink.internal.BoundedAttempt
import app.warplink.internal.Cancellable
import app.warplink.internal.unexpectedStatus
import app.warplink.referral.ReferralClaimStatus
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

/**
 * One claim request to `POST /v1/referrals/claims`.
 *
 * Claiming is idempotent on the server for one installer and app, so a retried
 * request cannot credit twice.
 *
 * [onTokenRefused] runs when the server refuses the token the claim carried.
 * The caller still gets the `none` answer; the callback lets it drop a token
 * that every later claim would see refused again.
 *
 * @return the request now on the wire.
 */
internal fun ApiClient.claimReferral(
    installerId: String,
    evidence: ReferralClaimEvidence,
    timeoutMs: Int?,
    onTokenRefused: () -> Unit,
    callback: (Result<ReferralClaimResult>) -> Unit,
): Cancellable {
    val attempt = BoundedAttempt(timeoutMs, mainHandler, executor)
    executor.execute {
        val result = try {
            val conn = makeConnection("/referrals/claims", method = "POST", timeoutMs = timeoutMs)
            attempt.open(conn)
            try {
                writeBody(conn, evidence.body(installerId, packageName))
                claimResult(conn, onTokenRefused)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(WarpLinkError.NetworkError(e))
        }
        val settled = attempt.settle(result)
        mainHandler.post { callback(settled) }
    }
    return Cancellable { attempt.cancel() }
}

private fun claimResult(
    conn: HttpURLConnection,
    onTokenRefused: () -> Unit,
): Result<ReferralClaimResult> = when (conn.responseCode) {
    200 -> Result.success(ReferralClaimDecoder.decode(readBody(conn)))
    401, 403 -> Result.failure(WarpLinkError.InvalidApiKey)
    else -> {
        val failure = conn.referralFailureOrNull() ?: unexpectedStatus(conn)
        if (ReferralMessages.isInvitationRefusal(failure)) {
            // A refused token is not a failed claim. The request carried the
            // token and nothing else that names a referral, so the server has
            // nothing to credit, which is the `none` answer.
            onTokenRefused()
            Result.success(ReferralClaimResult(ReferralClaimStatus.NONE))
        } else {
            Result.failure(failure)
        }
    }
}

private fun readBody(conn: HttpURLConnection): String? = try {
    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
} catch (_: java.io.IOException) {
    null
}
