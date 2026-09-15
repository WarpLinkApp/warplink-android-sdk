package app.warplink.internal

/**
 * Device-side signals sent to the server's raw-signals attribution path. The
 * server derives the request IP itself and computes the fingerprint from
 * `ip | normalizeLang(acceptLanguage) | timezone`, falling back to the offset.
 *
 * User-Agent and screen dimensions are intentionally NOT collected: they never
 * match between the browser (click time) and the native app (install time), so
 * they were dropped from the fingerprint hash.
 */
internal data class DeviceSignals(
    val acceptLanguage: String,
    val timezoneOffset: Int,
    /** IANA zone name, e.g. "America/Toronto". Empty when unavailable. */
    val timezone: String = ""
)
