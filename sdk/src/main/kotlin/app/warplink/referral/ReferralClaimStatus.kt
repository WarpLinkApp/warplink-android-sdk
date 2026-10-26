package app.warplink.referral

/** What a referral claim found. */
enum class ReferralClaimStatus(val wireValue: String) {
    /** The referral was credited to this installer. */
    CLAIMED("claimed"),

    /** Credited, but marked for review, for example a claim made after the window. */
    FLAGGED("flagged"),

    /** Not credited again: the installer or the app already holds a claim. */
    IGNORED("ignored"),

    /** No referral was found for this installer. An answer, not an error. */
    NONE("none");

    companion object {
        /** The status for [value], or null for one this SDK does not know. */
        fun fromWireValue(value: String?): ReferralClaimStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}
