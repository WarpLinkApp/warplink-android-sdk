package app.warplink.referral

/** How strong the evidence behind a match is. */
enum class EvidenceClass(val wireValue: String) {
    EXACT("exact"),
    ESTIMATED("estimated"),

    /** Matched through the link id alone, with no token behind it. */
    LEGACY_LINK("legacy_link");

    companion object {
        /** The class for [value], or null for one this SDK does not know. */
        fun fromWireValue(value: String?): EvidenceClass? =
            entries.firstOrNull { it.wireValue == value }
    }
}
