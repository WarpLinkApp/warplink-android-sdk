package app.warplink.internal

import app.warplink.WarpLinkError
import app.warplink.internal.referral.referralFailureOrNull
import org.json.JSONException

/**
 * @param isReinstall tags an install that follows an earlier, already
 * attributed install on the same device. Deliberately has NO default: the
 * referrer branch of the deferred check cannot be driven end to end in a
 * unit test (the Play Install Referrer client needs a real store), so a
 * caller that forgets to answer has to fail the build rather than quietly
 * report every reinstall as a first install.
 * @param tetherCode a `wl1t_` test code the Play referrer carried, sent as
 * `tether_code` alone. Android reads no clipboard, so this is the only way a
 * Tether code reaches the SDK.
 * @param tapId, @param timeoutMs the same two the resolve takes, for the
 * same reasons. On this path the tap id is log-only: the install row's
 * dedupe key is derived on the server from the stored payload, never
 * supplied by the client.
 * @param installationId, @param attemptNumber the traffic fields the server
 * reads, see [buildAttributionRequestBody].
 * @param onNetworkThread answers on the thread that read the response instead of
 * the main thread, so the caller can save what it needs before any hand-off.
 * @return the request now on the wire, so an abandoned check can stop it.
 */
internal fun ApiClient.matchAttribution(
    signals: DeviceSignals?,
    sdkVersion: String,
    deviceId: String?,
    referrer: String? = null,
    installReferrer: String? = null,
    isReinstall: Boolean,
    recoveryToken: String? = null,
    tetherCode: String? = null,
    tapId: String? = null,
    timeoutMs: Int? = null,
    installationId: String? = null,
    attemptNumber: Int? = null,
    onNetworkThread: Boolean = false,
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
                    signals, sdkVersion, deviceId, referrer, isReinstall, recoveryToken,
                    tetherCode, installationId, attemptNumber, installReferrer
                ))
                when (conn.responseCode) {
                    200 -> Result.success(parseAttribution(readResponse(conn)))
                    401, 403 -> Result.failure(
                        WarpLinkError.InvalidApiKey
                    )
                    else -> Result.failure(
                        conn.referralFailureOrNull() ?: unexpectedStatus(conn)
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
        if (onNetworkThread) callback(settled) else mainHandler.post { callback(settled) }
    }
    return Cancellable { attempt.cancel() }
}
