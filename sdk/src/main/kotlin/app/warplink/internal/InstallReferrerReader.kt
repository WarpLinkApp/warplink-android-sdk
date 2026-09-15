package app.warplink.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

internal class InstallReferrerReader(
    private val context: Context,
    private val logger: Logger? = null,
    /**
     * Builds the Play client. Injectable because every branch below is a
     * failure branch, and the real client needs a Play Store that no unit test
     * has. Without this seam the only way to reach them is to stand up the real
     * client under Robolectric, which never answers, costs minutes per test,
     * and leaves a pending connection that slows every test after it.
     */
    private val clientFactory: (Context) -> InstallReferrerClient = {
        InstallReferrerClient.newBuilder(it).build()
    }
) : ReferrerSource {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Referrer is Android's deterministic attribution path, and every way it
     * can fail produces the same `null` an organic install produces. Naming
     * the cause is the only way a developer can tell "no referrer" from
     * "referrer never worked".
     */
    private fun degraded(cause: String) {
        logger?.log("$LOG_PREFIX unavailable: $cause")
    }

    override fun readReferrer(callback: (Result<ReferrerRead?>) -> Unit) {
        val client: InstallReferrerClient
        try {
            client = clientFactory(context)
        } catch (e: Exception) {
            degraded("client could not be built (${e.javaClass.simpleName})")
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
            degraded("Play Store did not respond within ${TIMEOUT_MS}ms")
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
                        val read = handleResponse(
                            responseCode, client
                        )
                        safeEndConnection(client)
                        mainHandler.post {
                            callback(Result.success(read))
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        synchronized(lock) {
                            if (callbackInvoked) return
                            callbackInvoked = true
                        }
                        degraded("service disconnected before responding")
                        mainHandler.post {
                            callback(Result.success(null))
                        }
                    }
                }
            )
        } catch (e: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            synchronized(lock) {
                if (callbackInvoked) return
                callbackInvoked = true
            }
            degraded("connection could not be started (${e.javaClass.simpleName})")
            safeEndConnection(client)
            callback(Result.success(null))
        }
    }

    private fun handleResponse(
        responseCode: Int,
        client: InstallReferrerClient
    ): ReferrerRead? {
        if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
            degraded(InstallReferrerResponseCodes.describe(responseCode))
            return null
        }
        return try {
            val details = client.installReferrer
            val linkId = parseWarpLinkReferrer(details.installReferrer)
            // A referrer that is present but not ours is the ordinary organic
            // case, so it is reported as normal rather than as degraded.
            logger?.log(
                if (linkId == null) "$LOG_PREFIX carried no WarpLink link id"
                else "$LOG_PREFIX matched link $linkId"
            )
            linkId?.let { ReferrerRead(it, details.installBeginTimestampSeconds) }
        } catch (e: Exception) {
            degraded("referrer could not be read (${e.javaClass.simpleName})")
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
        private const val LOG_PREFIX = "Install referrer"

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
