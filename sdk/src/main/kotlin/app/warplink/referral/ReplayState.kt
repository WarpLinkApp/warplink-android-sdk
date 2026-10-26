package app.warplink.referral

/** Whether an answer is the first one for a match or a repeat of it. */
enum class ReplayState(val wireValue: String) {
    ORIGINAL("original"),
    REPLAYED("replayed");

    companion object {
        /** The state for [value], or null for one this SDK does not know. */
        fun fromWireValue(value: String?): ReplayState? =
            entries.firstOrNull { it.wireValue == value }
    }
}
