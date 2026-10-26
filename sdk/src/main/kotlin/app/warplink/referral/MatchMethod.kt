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
        /** The method for [value], or null for one this SDK does not know. */
        fun fromWireValue(value: String?): MatchMethod? =
            entries.firstOrNull { it.wireValue == value }
    }
}
