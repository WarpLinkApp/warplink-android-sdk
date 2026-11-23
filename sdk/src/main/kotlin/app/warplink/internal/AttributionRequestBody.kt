package app.warplink.internal

import org.json.JSONObject

private const val MAX_INSTALL_REFERRER_LENGTH = 512

/**
 * The JSON body of `POST /attribution/match`.
 *
 * [isReinstall] is a flag only: a reinstall is still an install and is still
 * attributed, so it is never a reason to skip the request. It is set for every
 * match path.
 *
 * Signals ride alongside the referrer. When the referrer names a deleted or
 * foreign link, the server's cascade falls through to the raw signals in this
 * same body; a body carrying nothing but the referrer loses the install for
 * good. `fingerprint_version` is required on every request and must describe
 * what was actually sent, so it is only `basic` when there is nothing else.
 *
 * [installationId] rides on every match request. [attemptNumber] only on the
 * deferred check's, which counts its launches. [installReferrer] is the Play
 * referrer whole, next to `referrer`, which stays the parsed link id. It is
 * trimmed to the server's 512 characters and left out when empty.
 */
internal fun buildAttributionRequestBody(
    packageName: String?,
    signals: DeviceSignals?,
    sdkVersion: String,
    deviceId: String?,
    referrer: String?,
    isReinstall: Boolean,
    recoveryToken: String?,
    tetherCode: String?,
    installationId: String? = null,
    attemptNumber: Int? = null,
    installReferrer: String? = null,
): JSONObject {
    val body = JSONObject()
    body.put("platform", "android")
    body.put("sdk_version", sdkVersion)
    body.put("is_reinstall", isReinstall)
    // Omitted rather than sent empty: the server rejects an empty value, which
    // would fail the whole request instead of falling back to the org-wide
    // lookup.
    if (!packageName.isNullOrEmpty()) {
        body.put("app_package_name", packageName)
    }
    if (deviceId != null) {
        body.put("device_id", deviceId)
    }
    if (installationId != null) {
        body.put("installation_id", installationId)
    }
    if (attemptNumber != null) {
        body.put("attempt_number", attemptNumber)
    }
    if (referrer != null) {
        body.put("referrer", referrer)
    }
    installReferrer?.trim()?.take(MAX_INSTALL_REFERRER_LENGTH)?.takeIf { it.isNotEmpty() }?.let {
        body.put("install_referrer", it)
    }
    if (recoveryToken != null) {
        body.put("recovery_token", recoveryToken)
    }
    if (tetherCode != null) {
        body.put("tether_code", tetherCode)
        body.put("sdk_platform", "android")
    }
    if (signals != null) {
        putFingerprintSignals(body, signals)
    } else {
        body.put("fingerprint_version", "basic")
    }
    return body
}

/**
 * Raw-signals enriched path: only accept_language + timezone. User-Agent and
 * screen dimensions were dropped from the server match key, since they
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
