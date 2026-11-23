package app.warplink.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * How often the deferred check may ask the server, and how old an install may be
 * before it stops asking.
 *
 * The SDK ships [DEFAULT] and uses the copy the server last sent. Every value the
 * server sends is clamped, so a bad answer can neither silence the check for
 * good nor make it call in a tight loop.
 */
internal data class SdkPolicy(
    /** The wait before the next check after the 1st, 2nd, ... failed check. The last value repeats. */
    val retryDelaysSeconds: List<Long>,
    /** The check stops for good this long after the first failed check. */
    val giveUpAfterSeconds: Long,
    /** An install older than this sends no request. */
    val maxInstallAgeSeconds: Long,
    /** An install older than this sends no raw device signals. */
    val rawSignalMaxInstallAgeSeconds: Long,
) {

    /** The wait in seconds after [failureCount] failed checks, 1-based. */
    fun retryDelayAfterFailure(failureCount: Int): Long =
        retryDelaysSeconds[(failureCount - 1).coerceIn(0, retryDelaysSeconds.lastIndex)]

    fun toJson(): JSONObject = JSONObject()
        .put(RETRY_DELAYS, JSONArray(retryDelaysSeconds))
        .put(GIVE_UP_AFTER, giveUpAfterSeconds)
        .put(MAX_INSTALL_AGE, maxInstallAgeSeconds)
        .put(RAW_SIGNAL_MAX_INSTALL_AGE, rawSignalMaxInstallAgeSeconds)

    companion object {
        private const val RETRY_DELAYS = "retry_delays_seconds"
        private const val GIVE_UP_AFTER = "give_up_after_seconds"
        private const val MAX_INSTALL_AGE = "max_install_age_seconds"
        private const val RAW_SIGNAL_MAX_INSTALL_AGE = "raw_signal_max_install_age_seconds"

        private const val MINUTE = 60L
        private const val HOUR = 3_600L
        private const val DAY = 86_400L

        val DEFAULT = SdkPolicy(
            retryDelaysSeconds = listOf(900L, HOUR, 6 * HOUR, DAY),
            giveUpAfterSeconds = 3 * DAY,
            maxInstallAgeSeconds = 30 * DAY,
            rawSignalMaxInstallAgeSeconds = 2 * DAY,
        )

        private val DELAY_RANGE = MINUTE..(7 * DAY)
        private val GIVE_UP_RANGE = HOUR..(30 * DAY)
        private val MAX_AGE_RANGE = DAY..(365 * DAY)

        /**
         * The block as the SDK will use it, or null when it is missing or holds no
         * usable value, so the caller keeps what it has. A field that is absent or
         * the wrong type keeps its default while the good fields apply.
         */
        fun fromJson(json: JSONObject?): SdkPolicy? {
            if (json == null) return null
            val delays = delaysIn(json)
            val numbers = listOf(GIVE_UP_AFTER, MAX_INSTALL_AGE, RAW_SIGNAL_MAX_INSTALL_AGE)
                .map { (json.opt(it) as? Number)?.toLong() }
            if (delays == null && numbers.all { it == null }) return null
            val maxAge = (numbers[1] ?: DEFAULT.maxInstallAgeSeconds).coerceIn(MAX_AGE_RANGE)
            return SdkPolicy(
                retryDelaysSeconds = delays ?: DEFAULT.retryDelaysSeconds,
                giveUpAfterSeconds = (numbers[0] ?: DEFAULT.giveUpAfterSeconds).coerceIn(GIVE_UP_RANGE),
                maxInstallAgeSeconds = maxAge,
                rawSignalMaxInstallAgeSeconds =
                    (numbers[2] ?: DEFAULT.rawSignalMaxInstallAgeSeconds).coerceIn(HOUR..maxAge),
            )
        }

        private fun delaysIn(json: JSONObject): List<Long>? {
            val array = json.optJSONArray(RETRY_DELAYS) ?: return null
            val delays = (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toLong() }
            if (delays.isEmpty() || delays.size != array.length()) return null
            return delays.map { it.coerceIn(DELAY_RANGE) }
        }
    }
}
