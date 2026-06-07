package app.warplink.internal

import android.os.Handler
import android.os.Looper
import app.warplink.WarpLink
import app.warplink.WarpLinkError
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal class ApiClient(
    private val apiKey: String,
    private val baseURL: String
) {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun validateApiKey(callback: (Result<Boolean>) -> Unit) {
        executor.execute {
            val result = try {
                val conn = makeConnection("/sdk/validate")
                try {
                    when (conn.responseCode) {
                        200 -> Result.success(true)
                        401, 403 -> Result.success(false)
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

    fun resolveLink(
        slug: String,
        domain: String,
        callback: (Result<LinkResponse>) -> Unit
    ) {
        executor.execute {
            val result = try {
                val path = "/links/resolve/$slug?domain=$domain"
                val conn = makeConnection(path)
                try {
                    when (conn.responseCode) {
                        200 -> Result.success(parseLink(conn))
                        404 -> Result.failure(WarpLinkError.LinkNotFound)
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
            mainHandler.post { callback(result) }
        }
    }

    fun matchAttribution(
        signals: DeviceSignals?,
        sdkVersion: String,
        deviceId: String?,
        referrer: String? = null,
        callback: (Result<AttributionResponse>) -> Unit
    ) {
        executor.execute {
            val result = try {
                val conn = makeConnection(
                    "/attribution/match",
                    method = "POST"
                )
                try {
                    writeBody(conn, buildAttributionBody(
                        signals, sdkVersion, deviceId, referrer
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
            mainHandler.post { callback(result) }
        }
    }

    private fun makeConnection(
        path: String,
        method: String = "GET"
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
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
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
            iosUrl = json.optString("ios_url").ifEmpty { null },
            androidUrl = json.optString("android_url").ifEmpty { null },
            customParams = parseCustomParams(
                json.optJSONObject("custom_params")
            )
        )
    }

    private fun parseAttribution(
        conn: HttpURLConnection
    ): AttributionResponse {
        val json = JSONObject(readResponse(conn))
        return AttributionResponse(
            matched = json.optBoolean("matched", false),
            matchType = json.optString("match_type").ifEmpty { null },
            matchConfidence = optDouble(json, "match_confidence"),
            linkId = json.optString("link_id").ifEmpty { null },
            deepLinkUrl = json.optString("deep_link_url").ifEmpty { null },
            destinationUrl = json.optString("destination_url")
                .ifEmpty { null },
            customParams = json.optJSONObject("custom_params")
                ?.let { parseCustomParams(it) },
            installId = json.optString("install_id").ifEmpty { null }
        )
    }

    // internal (not private) so unit tests can assert the wire-body contract.
    internal fun buildAttributionBody(
        signals: DeviceSignals?,
        sdkVersion: String,
        deviceId: String?,
        referrer: String?
    ): JSONObject {
        val body = JSONObject()
        body.put("platform", "android")
        body.put("sdk_version", sdkVersion)
        if (deviceId != null) {
            body.put("device_id", deviceId)
        }
        if (referrer != null) {
            body.put("referrer", referrer)
            // Schema requires fingerprint_version on every request; the
            // deterministic referrer path carries no enriched signals.
            body.put("fingerprint_version", "basic")
            return body
        }
        if (signals != null) {
            body.put("accept_language", signals.acceptLanguage)
            body.put("screen_width", signals.screenWidth)
            body.put("screen_height", signals.screenHeight)
            body.put("timezone_offset", signals.timezoneOffset)
            body.put("user_agent", signals.userAgent)
            body.put("fingerprint_version", "enriched")
        }
        return body
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

    private fun optDouble(json: JSONObject, key: String): Double? {
        return if (json.has(key)) json.getDouble(key) else null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
