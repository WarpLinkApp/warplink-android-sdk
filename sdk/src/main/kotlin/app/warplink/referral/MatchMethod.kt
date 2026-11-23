package app.warplink.referral

/**
 * How a match was made, as the server reports it. Stays with the result of the
 * match for good, including when the same match is later replayed.
 */
enum class MatchMethod(val wireValue: String) {
    /** A single-use recovery token the install carried. */
    INSTALL_TOKEN("install_token"),
    ANDROID_REFERRER("android_referrer"),
    DEVICE_ID("device_id"),

    /** The app was opened from the link on a device that already had it. */
    LINK_OPEN("link_open"),
    PROBABILISTIC("probabilistic");

    companion object {
        private const val SERVER_REFERRER = "referrer"

        /**
         * The method for [value], or null for one this SDK does not know. The
         * server names the install referrer match `referrer`, and rows from
         * before that name carry `android_referrer`. Both give [ANDROID_REFERRER].
         */
        fun fromWireValue(value: String?): MatchMethod? =
            if (value == SERVER_REFERRER) {
                ANDROID_REFERRER
            } else {
                entries.firstOrNull { it.wireValue == value }
            }
    }
}
