package app.warplink.internal

/**
 * What the Play Install Referrer said about this install, when it named a
 * WarpLink link.
 *
 * @property installBeginTimestampSeconds Seconds since the epoch when the Play
 * install began, from `ReferrerDetails.installBeginTimestampSeconds`; 0 when
 * Play did not say.
 */
internal data class ReferrerRead(
    val linkId: String,
    val installBeginTimestampSeconds: Long,
) {
    /**
     * Whether a match on this referrer should still be ROUTED. The referrer
     * outlives the install for months, so a check that runs long after the
     * install (a 1.0.x upgrade, or the first release a customer ships with the
     * SDK) still names the original link. Attributing that install is right.
     * Dropping a long-time user into months-old content is not. An unknown
     * timestamp routes: silence is not evidence of age.
     */
    fun isRoutable(nowSeconds: Long): Boolean =
        installBeginTimestampSeconds <= 0L ||
            nowSeconds - installBeginTimestampSeconds <= ROUTABLE_INSTALL_AGE_SECONDS

    companion object {
        /**
         * Seven days, the click-to-install window deferred deep linking is built
         * for. Bounded by the install, not the click: a user who taps, installs
         * and first opens a week later still gets their content.
         */
        const val ROUTABLE_INSTALL_AGE_SECONDS = 7L * 24 * 60 * 60
    }
}

/**
 * Where the deferred check gets its Play Install Referrer link id from.
 *
 * An interface rather than the concrete [InstallReferrerReader] because that
 * class talks to the Play Store, which no unit test can stand up. Without this
 * seam the deterministic branch of the check is untestable, and an untestable
 * branch is exactly where a hand-ported SDK loses a field.
 *
 * The callback carries null for "no WarpLink referrer", which is the normal
 * outcome for an organic install, not an error.
 */
internal fun interface ReferrerSource {
    fun readReferrer(callback: (Result<ReferrerRead?>) -> Unit)
}
