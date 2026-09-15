package app.warplink.internal

import java.util.Locale
import java.time.ZoneId
import java.util.TimeZone

/**
 * Collects the device signals the server's raw-signals attribution path needs:
 * the raw preferred language tag, the DST-aware timezone offset, and the IANA
 * zone name. The server normalizes the language and combines the rest with the
 * request IP to compute the fingerprint; nothing is hashed on device.
 *
 * Collection is total: it always returns signals and never throws. That is
 * deliberate, and it mirrors iOS's `FingerprintCollector.collect()`.
 *
 * The previous shape gathered all three inside one `try` and returned a
 * `Result`, which made the whole set hostage to its most fragile member. Only
 * one member was ever fragile, and which one it was kept moving: screen
 * metrics in 1.0.x, then the zone name. A single throw discarded the language
 * and the offset with it and aborted the deferred check before any request was
 * sent, so the install went unattributed with nothing logged and a raw JDK
 * exception delivered to the host's `onLink`.
 */
internal class FingerprintCollector {

    fun collect(): DeviceSignals = DeviceSignals(
        acceptLanguage = languageTag(),
        timezoneOffset = timezoneOffset(),
        timezone = timezoneName()
    )

    /**
     * Raw device preferred language, e.g. `"en-US"`. The server normalizes it;
     * do NOT pre-normalize here.
     */
    private fun languageTag(): String = try {
        Locale.getDefault().toLanguageTag()
    } catch (_: Exception) {
        ""
    }

    /**
     * Timezone offset in minutes, JS `getTimezoneOffset()` convention
     * (sign-inverted: UTC+2 → -120). Uses `getOffset(now)` so the value is
     * DST-aware; `rawOffset` would drop daylight saving time.
     */
    private fun timezoneOffset(): Int = try {
        -(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
    } catch (_: Exception) {
        0
    }

    /**
     * IANA zone name, or `""` when the process default zone has no tzdb entry.
     *
     * `ZoneId.systemDefault()` resolves `TimeZone.getDefault().id` against tzdb
     * and throws `ZoneRulesException` when there is no match, which host code
     * can cause by calling `TimeZone.setDefault()` with a custom-labelled zone.
     * The zone name is the one optional signal of the three, so losing it
     * degrades the match rather than cancelling it: `ApiClient` omits an empty
     * zone and the request still carries the language and the offset.
     *
     * Note the offset above reads the same `TimeZone.getDefault()` that a host
     * would have poisoned, so in that scenario it reports the fake zone's
     * offset. The two are not independent, and the fingerprint can still miss.
     * Sending a degraded request beats sending none: the referrer and device-id
     * tiers are unaffected, and a miss is recorded server-side where a
     * cancelled request is not.
     */
    private fun timezoneName(): String = try {
        ZoneId.systemDefault().id
    } catch (_: Exception) {
        ""
    }
}
