package app.warplink.internal.referral

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The stores the referral handoff uses, in one place so a test can replace the
 * clock and so one object can be wiped with the rest of the SDK's state.
 *
 * Both stores live under `noBackupFilesDir`: a record is honoured only for the
 * install that wrote it, so a restore or a reinstall never replays an old
 * invitation.
 */
internal class ReferralStores(directory: File) {

    val pending = ReferralPendingStore(directory)
    val evidence = ReferralEvidenceStore(directory)
    val referrerTap = ReferrerTapStore(directory)

    /** Seconds since the epoch. Replaced only by tests. */
    @Volatile
    var nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }

    private val redeemInFlight = AtomicBoolean(false)

    /**
     * Claims the right to send a redeem request. False while one is running, so
     * a paste and a launch resume that meet are collapsed into one request and
     * one token is delivered once.
     */
    fun beginRedeem(): Boolean = redeemInFlight.compareAndSet(false, true)

    fun endRedeem() = redeemInFlight.set(false)

    fun clear() {
        pending.clear()
        evidence.clear()
        referrerTap.clear()
        redeemInFlight.set(false)
    }
}
