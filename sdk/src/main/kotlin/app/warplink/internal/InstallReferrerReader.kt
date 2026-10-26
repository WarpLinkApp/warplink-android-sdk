package app.warplink.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import app.warplink.internal.referral.WarpLinkReferrer
import app.warplink.internal.referral.parseWarpLinkReferrer
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
            val parsed = parseWarpLinkReferrer(details.installReferrer)
            // A referrer that is present but not ours is the ordinary organic
            // case, so it is reported as normal rather than as degraded.
            logger?.log(describe(parsed))
            parsed?.let {
                ReferrerRead(
                    it.linkId,
                    details.installBeginTimestampSeconds,
                    it.recoveryToken
                )
            }
        } catch (e: Exception) {
            degraded("referrer could not be read (${e.javaClass.simpleName})")
            null
        }
    }

    private fun describe(parsed: WarpLinkReferrer?): String = when {
        parsed == null -> "$LOG_PREFIX carried no WarpLink link id"
        parsed.linkId != null -> "$LOG_PREFIX matched link ${parsed.linkId}"
        else -> "$LOG_PREFIX carried a recovery token"
    }

    private fun safeEndConnection(client: InstallReferrerClient) {
        try {
            client.endConnection()
        } catch (e: Exception) {
            degraded("connection did not close cleanly (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2000L
        const val LOG_PREFIX = "Install referrer"
    }
}
