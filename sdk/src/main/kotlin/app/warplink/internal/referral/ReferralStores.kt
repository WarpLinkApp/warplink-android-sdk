package app.warplink.internal.referral

import app.warplink.internal.KeyEnvironment
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
internal class ReferralStores(
    directory: File,
    environment: KeyEnvironment = KeyEnvironment.PRODUCTION,
) {

    val pending = ReferralPendingStore(directory, environment)
    val evidence = ReferralEvidenceStore(directory, environment)
    val referrerTap = ReferrerTapStore(directory, environment)

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

    /**
     * Counts the times redeems started so far were retired. A run captures it
     * at start and writes only while it still matches, so an answer that lands
     * after a clear or a reconfigure refills nothing.
     */
    @Volatile
    var epoch: Int = 0
        private set

    /** Retires every redeem started so far. Their answers are dropped. */
    @Synchronized
    fun orphanRedeems() {
        epoch++
    }

    /**
     * Deletes the records and retires the redeems running now. Does not free
     * the redeem claim: the retired run still holds it and releases it itself,
     * so it cannot release a newer run's claim. True when every record is gone.
     */
    fun clearRecords(): Boolean {
        orphanRedeems()
        return listOf(pending.clear(), evidence.clear(), referrerTap.clear()).all { it }
    }

    fun clear() {
        clearRecords()
        redeemInFlight.set(false)
    }
}
