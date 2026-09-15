package app.warplink.internal

import android.os.Handler
import android.os.Looper
import app.warplink.WarpLink
import app.warplink.WarpLinkError
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * @param packageName the host app's application ID, sent with attribution
 * requests so the server can tell which app of an organization an install
 * belongs to. An API key is org-scoped, so without it the server can match an
 * install to a sibling app. Null when the caller has no context to read it
 * from, in which case the key is omitted from the body.
 */
/**
 * The one call [performServerValidation] needs.
 *
 * Narrow on purpose: validation is the only part of the SDK that has to be
 * driven from a test without a socket, and depending on the whole ApiClient
 * would mean faking a class that also does resolve and attribution.
 */
internal fun interface ApiKeyValidating {
    fun validateApiKey(callback: (Result<ValidateResponse>) -> Unit)
}

internal class ApiClient(
    private val apiKey: String,
    private val baseURL: String,
    private val packageName: String? = null
) : ApiKeyValidating, RetryScheduler {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The delay is scheduled on the main handler because it is the only timer
     * the SDK has, but the body hops straight back onto the executor:
     * HttpURLConnection I/O on the main looper throws
     * NetworkOnMainThreadException on a real device.
     */
    override fun schedule(delayMs: Long, block: () -> Unit) {
        mainHandler.postDelayed({ executor.execute(block) }, delayMs)
    }

    override fun validateApiKey(callback: (Result<ValidateResponse>) -> Unit) {
        executor.execute {
            val result = try {
                val conn = makeConnection("/sdk/validate")
                try {
                    when (conn.responseCode) {
                        200 -> Result.success(parseValidate(conn))
                        401, 403 -> Result.success(
                            ValidateResponse(valid = false, domains = emptyList())
                        )
                        else -> Result.failure(
                            WarpLinkError.ServerError(
                                conn.responseCode,
                                "Unexpected status"
                            )
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Result.failure(WarpLinkError.NetworkError(e))
            }
            mainHandler.post { callback(result) }
        }
    }

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
    fun resolveLink(
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
                        200 -> Result.success(parseLink(conn))
                        // Also what a suspended custom domain answers with,
                        // deliberately: the server refuses to say a domain
                        // exists but is not serving.
                        404 -> Result.failure(WarpLinkError.LinkNotFound)
                        403 -> Result.failure(refusalFor(conn))
                        401 -> Result.failure(
                            WarpLinkError.InvalidApiKey
                        )
                        else -> Result.failure(
                            WarpLinkError.ServerError(
                                conn.responseCode,
                                "Unexpected status"
                            )
                        )
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

    /**
     * @param isReinstall tags an install that follows an earlier, already
     * attributed install on the same device. Deliberately has NO default: the
     * referrer branch of the deferred check cannot be driven end to end in a
     * unit test (the Play Install Referrer client needs a real store), so a
     * caller that forgets to answer has to fail the build rather than quietly
     * report every reinstall as a first install.
     * @param tapId, @param timeoutMs the same two the resolve takes, for the
     * same reasons. On this path the tap id is log-only: the install row's
     * dedupe key is derived on the server from the stored payload, never
     * supplied by the client.
     * @return the request now on the wire, so an abandoned check can stop it.
     */
    fun matchAttribution(
        signals: DeviceSignals?,
        sdkVersion: String,
        deviceId: String?,
        referrer: String? = null,
        isReinstall: Boolean,
        tapId: String? = null,
        timeoutMs: Int? = null,
        callback: (Result<AttributionResponse>) -> Unit
    ): Cancellable {
        val attempt = BoundedAttempt(timeoutMs, mainHandler, executor)
        executor.execute {
            val result = try {
                val conn = makeConnection(
                    "/attribution/match",
                    method = "POST",
                    tapId = tapId,
                    timeoutMs = timeoutMs
                )
                attempt.open(conn)
                try {
                    writeBody(conn, buildAttributionBody(
                        signals, sdkVersion, deviceId, referrer, isReinstall
                    ))
                    when (conn.responseCode) {
                        200 -> Result.success(parseAttribution(conn))
                        401, 403 -> Result.failure(
                            WarpLinkError.InvalidApiKey
                        )
                        else -> Result.failure(
                            WarpLinkError.ServerError(
                                conn.responseCode,
                                "Unexpected status"
                            )
                        )
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

    private fun makeConnection(
        path: String,
        method: String = "GET",
        tapId: String? = null,
        timeoutMs: Int? = null
    ): HttpURLConnection {
        val url = URL("$baseURL$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty(
            "Authorization", "Bearer $apiKey"
        )
        conn.setRequestProperty(
            "User-Agent", "WarpLink-Android/${WarpLink.SDK_VERSION}"
        )
        if (tapId != null) {
            conn.setRequestProperty(TAP_ID_HEADER, tapId)
        }
        // Set on EVERY attempt, the first included: the class defaults are 15s
        // and 30s, which is the whole retry budget and then some. These bound
        // the CONNECT and the gap between bytes; BoundedAttempt bounds the
        // attempt itself, which is the only thing a dripping answer respects.
        conn.connectTimeout = timeoutMs ?: CONNECT_TIMEOUT_MS
        conn.readTimeout = timeoutMs ?: READ_TIMEOUT_MS
        return conn
    }

    private fun writeBody(
        conn: HttpURLConnection,
        body: JSONObject
    ) {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Tells the two 403s apart. Resolve answers 403 for a rejected key AND for
     * a password protected link, and only the body's error code separates
     * them. A refusal the SDK cannot read keeps the older meaning rather than
     * accusing every 403 of being password protected.
     */
    private fun refusalFor(conn: HttpURLConnection): WarpLinkError {
        return if (errorCodeOf(conn) == PASSWORD_REQUIRED) {
            WarpLinkError.PasswordRequired
        } else {
            WarpLinkError.InvalidApiKey
        }
    }

    /**
     * The API's error code, from the documented `{"error":{"code":...}}` body.
     *
     * A refusal has no body on `inputStream`; reading it throws. Returns null
     * when the body is absent or unreadable, which the caller maps to the
     * generic refusal. Deliberately not rethrown: a malformed error body would
     * otherwise surface as a decoding failure and hide the refusal itself.
     */
    private fun errorCodeOf(conn: HttpURLConnection): String? {
        return try {
            val body = conn.errorStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .use { it.readText() }
            } ?: return null
            JSONObject(body).optJSONObject("error")?.optStringOrNull("code")
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        return BufferedReader(
            InputStreamReader(conn.inputStream, Charsets.UTF_8)
        ).use { it.readText() }
    }

    private fun parseLink(conn: HttpURLConnection): LinkResponse {
        val json = JSONObject(readResponse(conn))
        return LinkResponse(
            id = json.getString("id"),
            slug = json.getString("slug"),
            destinationUrl = json.getString("destination_url"),
            iosUrl = json.optStringOrNull("ios_url"),
            androidUrl = json.optStringOrNull("android_url"),
            customParams = parseCustomParams(
                json.optJSONObject("custom_params")
            )
        )
    }

    private fun parseValidate(conn: HttpURLConnection): ValidateResponse {
        val json = JSONObject(readResponse(conn))
        val domainsArray = json.optJSONArray("domains")
        val domains = if (domainsArray == null) {
            emptyList()
        } else {
            (0 until domainsArray.length())
                .mapNotNull { domainsArray.optStringOrNull(it) }
        }
        return ValidateResponse(
            valid = json.optBoolean("valid", true),
            domains = domains
        )
    }

    private fun parseAttribution(
        conn: HttpURLConnection
    ): AttributionResponse {
        val json = JSONObject(readResponse(conn))
        return AttributionResponse(
            matched = json.optBoolean("matched", false),
            matchType = json.optStringOrNull("match_type"),
            matchConfidence = json.optDoubleOrNull("match_confidence"),
            matchGuaranteed = json.optBoolean("match_guaranteed", false),
            linkId = json.optStringOrNull("link_id"),
            deepLinkUrl = json.optStringOrNull("deep_link_url"),
            destinationUrl = json.optStringOrNull("destination_url"),
            customParams = json.optJSONObject("custom_params")
                ?.let { parseCustomParams(it) },
            installId = json.optStringOrNull("install_id")
        )
    }

    // internal (not private) so unit tests can assert the wire-body contract.
    internal fun buildAttributionBody(
        signals: DeviceSignals?,
        sdkVersion: String,
        deviceId: String?,
        referrer: String?,
        // Defaulted here, unlike matchAttribution, whose callers must answer.
        // The only production caller is matchAttribution itself, and that hop is
        // covered end to end, so the default serves tests about other fields.
        isReinstall: Boolean = false
    ): JSONObject {
        val body = JSONObject()
        body.put("platform", "android")
        body.put("sdk_version", sdkVersion)
        // A reinstall is still an install and is still attributed. The flag only
        // lets the two be told apart afterwards, so it is never a reason to skip
        // the request. Set for both match paths.
        body.put("is_reinstall", isReinstall)
        // Set for both match paths.
        // Omitted rather than sent empty: the server rejects an empty value,
        // which would fail the whole request instead of falling back to the
        // org-wide lookup.
        if (!packageName.isNullOrEmpty()) {
            body.put("app_package_name", packageName)
        }
        if (deviceId != null) {
            body.put("device_id", deviceId)
        }
        if (referrer != null) {
            body.put("referrer", referrer)
        }
        // Signals ride alongside the referrer. When the referrer names a deleted
        // or foreign link, the server's cascade falls through to the raw signals
        // in this same body; a body carrying nothing but the referrer loses the
        // install for good. fingerprint_version is required on every request
        // and must describe what was actually sent, so it is only `basic` when
        // there is genuinely nothing else.
        if (signals != null) {
            putFingerprintSignals(body, signals)
        } else {
            body.put("fingerprint_version", "basic")
        }
        return body
    }

    /**
     * Raw-signals enriched path: only accept_language + timezone. User-Agent and
     * screen dimensions were dropped from the server fingerprint, since they
     * never match between the browser (click) and the native app (install).
     */
    private fun putFingerprintSignals(body: JSONObject, signals: DeviceSignals) {
        body.put("accept_language", signals.acceptLanguage)
        // Offset stays for servers that have not shipped zone-name matching.
        body.put("timezone_offset", signals.timezoneOffset)
        if (signals.timezone.isNotEmpty()) {
            // The IANA zone carries far more entropy than the offset and does
            // not shift at a daylight-saving boundary, so the server prefers it.
            body.put("timezone", signals.timezone)
            body.put("fingerprint_version", "enriched_tz")
        } else {
            body.put("fingerprint_version", "enriched")
        }
    }

    private fun parseCustomParams(
        json: JSONObject?
    ): Map<String, Any> {
        if (json == null) return emptyMap()
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            map[key] = json.get(key)
        }
        return map
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * One id per tap, repeated on every attempt of that tap. On resolve the
         * server uses it as the click's id. On attribution it is log-only: the
         * install row's dedupe key is server-derived, not client supplied.
         */
        const val TAP_ID_HEADER = "X-WarpLink-Tap-Id"

        /** The API's error code for a link whose password was not supplied. */
        private const val PASSWORD_REQUIRED = "PASSWORD_REQUIRED"
    }
}
