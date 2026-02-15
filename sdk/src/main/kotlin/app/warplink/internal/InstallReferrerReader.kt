package app.warplink.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

internal class InstallReferrerReader(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun readReferrer(callback: (Result<String?>) -> Unit) {
        val client: InstallReferrerClient
        try {
            client = InstallReferrerClient.newBuilder(context).build()
        } catch (_: Exception) {
            callback(Result.success(null))
            return
        }

        var callbackInvoked = false
        val lock = Any()

        val timeoutRunnable = Runnable {
            synchronized(lock) {
                if (callbackInvoked) return@Runnable
                callbackInvoked = true
            }
            safeEndConnection(client)
            callback(Result.success(null))
        }

        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        try {
            client.startConnection(
                object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(
                        responseCode: Int
                    ) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        synchronized(lock) {
                            if (callbackInvoked) return
                            callbackInvoked = true
                        }
                        val linkId = handleResponse(
                            responseCode, client
                        )
                        safeEndConnection(client)
                        mainHandler.post {
                            callback(Result.success(linkId))
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        synchronized(lock) {
                            if (callbackInvoked) return
                            callbackInvoked = true
                        }
                        mainHandler.post {
                            callback(Result.success(null))
                        }
                    }
                }
            )
        } catch (_: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            synchronized(lock) {
                if (callbackInvoked) return
                callbackInvoked = true
            }
            safeEndConnection(client)
            callback(Result.success(null))
        }
    }

    private fun handleResponse(
        responseCode: Int,
        client: InstallReferrerClient
    ): String? {
        if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
            return null
        }
        return try {
            val referrer = client.installReferrer.installReferrer
            parseWarpLinkReferrer(referrer)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeEndConnection(client: InstallReferrerClient) {
        try {
            client.endConnection()
        } catch (_: Exception) {
            // Ignore — client may already be disconnected
        }
    }

    companion object {
        private const val TIMEOUT_MS = 2000L
        private val UUID_REGEX = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}" +
                "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )

        internal fun parseWarpLinkReferrer(
            referrerString: String
        ): String? {
            if (referrerString.isBlank()) return null
            return try {
                val decoded = java.net.URLDecoder.decode(
                    referrerString, "UTF-8"
                )
                val params = parseQueryParams(decoded)
                val source = params["utm_source"]
                if (source != "warplink") return null
                val content = params["utm_content"] ?: return null
                if (UUID_REGEX.matches(content)) content else null
            } catch (_: Exception) {
                null
            }
        }

        private fun parseQueryParams(
            query: String
        ): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    if (!map.containsKey(key)) {
                        map[key] = parts[1]
                    }
                }
            }
            return map
        }
    }
}
