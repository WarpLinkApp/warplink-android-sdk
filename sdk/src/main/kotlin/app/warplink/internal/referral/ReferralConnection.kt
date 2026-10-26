package app.warplink.internal.referral

import app.warplink.WarpLinkError
import app.warplink.internal.RateLimit
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection

/** The `error` object of an API error body. */
internal data class ErrorBody(val code: String?, val message: String?)

/**
 * The `{"error":{"code","message"}}` body of a refusal, or an empty one when
 * the body is absent or unreadable. An unreadable body is never rethrown: it
 * would hide the refusal behind a decoding failure.
 */
internal fun HttpURLConnection.readErrorBody(): ErrorBody {
    val text = try {
        errorStream?.let { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        }
    } catch (_: IOException) {
        null
    } ?: return ErrorBody(null, null)
    return try {
        val error = JSONObject(text).optJSONObject("error")
        ErrorBody(error?.optString("code")?.ifEmpty { null }, error?.optString("message")?.ifEmpty { null })
    } catch (_: JSONException) {
        ErrorBody(null, null)
    }
}

/**
 * The failure for a response whose status carries a referral meaning, or null
 * for any other status, which the caller words as it always did.
 *
 * Only the two statuses the recovery-token rules add are read here: a 400 that
 * refuses the token and a 503 that says the token is not confirmed yet.
 */
internal fun HttpURLConnection.referralFailureOrNull(): WarpLinkError? {
    val status = responseCode
    if (status != ReferralMessages.REFUSAL_STATUS && status != ReferralMessages.PENDING_STATUS) return null
    val body = readErrorBody()
    val known = body.code in KNOWN_CODES
    if (!known) return null
    val retryAfter = RateLimit.parseRetryAfter(getHeaderField("Retry-After"))
    return ReferralHttp.failure(status, body.code, body.message, retryAfter)
}

private val KNOWN_CODES = setOf(
    ReferralHttp.TOKEN_EXPIRED_CODE,
    ReferralHttp.TOKEN_INVALID_CODE,
    ReferralHttp.TOKEN_PENDING_CODE,
)
