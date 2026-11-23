package app.warplink.internal

import app.warplink.WarpLinkError
import org.json.JSONException
import java.net.URLEncoder

/**
 * @param params the tapped URL's raw, percent-encoded query string, as
 * `Uri.encodedQuery` gives it. The server decodes it once and parses the
 * result as a query string, so raw is the only form that survives: a
 * decoded source turns `x%26y` into two parameters and reads the `+` in
 * `a%2Bb%40x.com` as a space. The server reshapes the link's URLs with it,
 * so a link's UTM and custom parameters survive an in-app open exactly as
 * they survive a redirect. Null when the tapped URL carried no query.
 * @param tapId one id for the tap this request belongs to, repeated on
 * every attempt of that tap. The server uses it as the click's id, so an
 * attempt whose response was lost does not bill a second click. Null when
 * the caller is not retrying.
 * @param timeoutMs this attempt's own connect and read timeouts. The
 * bounded retry sets them on EVERY attempt, the first included: an
 * unbounded attempt 1 hangs for CONNECT_TIMEOUT_MS or READ_TIMEOUT_MS,
 * spends the whole retry budget by itself, and no retry ever runs. Null
 * only for a caller that is not retrying.
 * @return the request now on the wire, so a superseding caller can stop it
 * rather than merely ignore its answer.
 */
internal fun ApiClient.resolveLink(
    slug: String,
    domain: String,
    params: String? = null,
    tapId: String? = null,
    timeoutMs: Int? = null,
    callback: (Result<LinkResponse>) -> Unit
): Cancellable {
    // Holds the connection for whoever supersedes this request and for the
    // attempt's own watchdog, so disconnect() can interrupt a read that is
    // already blocked. The `finally` below still runs; twice is harmless.
    val attempt = BoundedAttempt(timeoutMs, mainHandler, executor)
    executor.execute {
        val result = try {
            val path = "/links/resolve/$slug?domain=$domain" + encodedParams(params)
            val conn = makeConnection(path, tapId = tapId, timeoutMs = timeoutMs)
            attempt.open(conn)
            try {
                when (conn.responseCode) {
                    200 -> Result.success(parseLink(readResponse(conn)))
                    // Also what a suspended custom domain answers with,
                    // deliberately: the server refuses to say a domain
                    // exists but is not serving.
                    404 -> Result.failure(WarpLinkError.LinkNotFound)
                    403 -> Result.failure(refusalFor(conn))
                    401 -> Result.failure(
                        WarpLinkError.InvalidApiKey
                    )
                    else -> Result.failure(unexpectedStatus(conn))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: JSONException) {
            Result.failure(WarpLinkError.DecodingError(e))
        } catch (e: Exception) {
            Result.failure(WarpLinkError.NetworkError(e))
        }
        val settled = attempt.settle(result)
        mainHandler.post { callback(settled) }
    }
    return Cancellable { attempt.cancel() }
}

/**
 * `&params=<once-encoded>`, or empty when there is nothing to send.
 *
 * [params] is the raw query string, so exactly one [URLEncoder] pass is
 * correct: it is transport encoding only, and the server's single decode
 * gives its query parser the raw bytes back. A second pass would reach the
 * server as the literal `%3D` rather than an `=`. `URLEncoder` escapes a
 * literal `+` to `%2B`, so a `+` that meant a space in the tapped URL still
 * means a space after the server parses it.
 */
private fun encodedParams(params: String?): String =
    params
        ?.takeIf { it.isNotEmpty() }
        ?.let { "&params=" + URLEncoder.encode(it, "UTF-8") }
        ?: ""
