package app.warplink.internal

import android.os.Handler
import android.os.Looper
import app.warplink.WarpLink
import app.warplink.WarpLinkError
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The HTTP client for the WarpLink API. [resolveLink] and [matchAttribution]
 * are extensions in their own files.
 *
 * @param packageName the host app's application ID, sent with attribution
 * requests so the server can tell which app of an organization an install
 * belongs to. An API key is org-scoped, so without it the server can match an
 * install to a sibling app. Null when the caller has no context to read it
 * from, in which case the key is omitted from the body.
 */
internal class ApiClient(
    private val apiKey: String,
    private val baseURL: String,
    internal val packageName: String? = null
) : ApiKeyValidating, RetryScheduler {

    internal val executor = Executors.newCachedThreadPool()
    internal val mainHandler = Handler(Looper.getMainLooper())

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
                        200 -> Result.success(parseValidate(readResponse(conn)))
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

    internal fun makeConnection(
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

    internal fun writeBody(
        conn: HttpURLConnection,
        body: JSONObject
    ) {
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }
    }

    internal fun readResponse(conn: HttpURLConnection): String {
        return BufferedReader(
            InputStreamReader(conn.inputStream, Charsets.UTF_8)
        ).use { it.readText() }
    }

    // internal (not private) so unit tests can assert the wire-body contract.
    // isReinstall is defaulted here, unlike matchAttribution, whose callers
    // must answer: the default serves tests about other fields.
    internal fun buildAttributionBody(
        signals: DeviceSignals?,
        sdkVersion: String,
        deviceId: String?,
        referrer: String?,
        isReinstall: Boolean = false,
        recoveryToken: String? = null,
        tetherCode: String? = null,
        installationId: String? = null,
        attemptNumber: Int? = null,
        installReferrer: String? = null
    ): JSONObject = buildAttributionRequestBody(
        packageName, signals, sdkVersion, deviceId, referrer, isReinstall,
        recoveryToken, tetherCode, installationId, attemptNumber, installReferrer
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * One id per tap, repeated on every attempt of that tap. On resolve the
         * server uses it as the click's id. On attribution it is log-only: the
         * install row's dedupe key is server-derived, not client supplied.
         */
        const val TAP_ID_HEADER = "X-WarpLink-Tap-Id"
    }
}
