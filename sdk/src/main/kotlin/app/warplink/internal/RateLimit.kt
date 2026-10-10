package app.warplink.internal

import app.warplink.WarpLinkError
import java.net.HttpURLConnection

/**
 * How a 429 reaches the retry driver and how long it asks the caller to wait.
 *
 * A 429 stays a [WarpLinkError.ServerError] with status 429, so no host `when`
 * over [WarpLinkError] gains a branch. The server's `Retry-After` rides in
 * [WarpLinkError.ServerError.retryAfterSeconds].
 */
internal object RateLimit {

    const val STATUS_CODE = 429

    /** The wait when the response carries no usable `Retry-After`. */
    const val DEFAULT_WAIT_MS = 1_000L

    /** A longer `Retry-After` than this goes to the caller instead of a retry. */
    const val MAX_WAIT_MS = 5_000L

    /** Delta-seconds only. An HTTP-date form counts as absent. */
    fun parseRetryAfter(header: String?): Double? =
        header?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    fun isRateLimited(error: Throwable): Boolean =
        error is WarpLinkError.ServerError && error.statusCode == STATUS_CODE

    /**
     * The milliseconds to wait before the one retry, or null when [error] is
     * not a 429 or asks for more than [MAX_WAIT_MS].
     */
    fun waitMs(error: Throwable): Long? {
        if (!isRateLimited(error)) return null
        val seconds = (error as WarpLinkError.ServerError).retryAfterSeconds
            ?: (DEFAULT_WAIT_MS / 1_000.0)
        if (seconds > MAX_WAIT_MS / 1_000.0) return null
        return Math.round(seconds * 1_000.0)
    }
}

/** The error for a status this client has no specific meaning for. */
internal fun unexpectedStatus(conn: HttpURLConnection): WarpLinkError.ServerError {
    val code = conn.responseCode
    val retryAfter = if (code == RateLimit.STATUS_CODE) {
        RateLimit.parseRetryAfter(conn.getHeaderField("Retry-After"))
    } else {
        null
    }
    return WarpLinkError.ServerError(code, "Unexpected status", retryAfter)
}
