package app.warplink.internal

import android.content.Context
import android.content.SharedPreferences
import app.warplink.WarpLinkDeepLink
import app.warplink.internal.referral.ReferralStores

/**
 * The device state of one key [environment]. Test and Production keep separate
 * preferences, markers and referral records, so a `wl_test_` build never reads,
 * writes or clears what a live build stored on the same device.
 */
internal class Storage(
    context: Context,
    val environment: KeyEnvironment = KeyEnvironment.PRODUCTION,
) {

    /**
     * Held while a check or redeem decides it is still current and writes its
     * result, and while [clearAttribution] clears. The two can never interleave,
     * so a late answer cannot refill cleared state. Never held across a host
     * callback.
     */
    val stateLock = Any()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(environment.scoped(StorageKeys.PREFS_NAME), Context.MODE_PRIVATE)

    private val completedMarker = environment.scoped(StorageKeys.MARKER_DEFERRED_COMPLETED)
    private val attemptedMarker = environment.scoped(StorageKeys.MARKER_DEFERRED_ATTEMPTED)

    // Two stores with deliberately opposite lifetimes, and which one a flag
    // lives in IS its meaning. Install-scoped markers die with the install (see
    // InstallMarkerStore), so a reinstall re-runs the check. SharedPreferences
    // is restored by Android Auto Backup, so it is the only place a fact about
    // the DEVICE can survive an uninstall.
    private val markers = InstallMarkerStore(context)

    /** The referral handoff's records: install-scoped, like [markers]. */
    val referral = ReferralStores(context.noBackupFilesDir, environment)

    // Read once, at construction, so every decision below sees the same
    // answer for the life of this instance (see InstallProvenance).
    private val firstInstallTime: Long = readFirstInstallTime(context)

    init {
        // A cached match with an open gate is a ghost. The two are written
        // together on a definitive response, and only Auto Backup can bring the
        // cache back without the gate. Left alone, attributionResult replays the
        // previous install's match until the new check lands. `isReinstall`, not
        // `!deferredCheckCompleted`: the broad form also fires on a genuine first
        // install whose gate is simply still open.
        if (isReinstall && prefs.contains(StorageKeys.CACHED_ATTRIBUTION)) {
            prefs.edit().remove(StorageKeys.CACHED_ATTRIBUTION).apply()
        }
    }

    /**
     * Whether the deferred deep link check has reached a definitive server
     * response (a match or a confirmed no-match). Consumed ONLY on that
     * response, so an offline first launch retries on the next launch instead
     * of silently swallowing the flag.
     */
    var deferredCheckCompleted: Boolean
        // 1.0.x wrote `is_first_launch = false` at the START of its check, before
        // a single byte left the device, so the flag proves only that a check
        // began. It is deliberately not read: gating on it locked every
        // upgrading install out of attribution permanently.
        get() = markers.isSet(completedMarker) || fallbackGateCoversThisInstall()
        set(value) {
            if (value) markCheckCompleted() else clearGate()
        }

    /**
     * The gate, mirrored in SharedPreferences ONLY when noBackupFilesDir could
     * not take it, and bound to firstInstallTime so it names this install. A
     * restored copy on a new install carries the old install's time and does
     * not match, so it cannot gate the new install (WL-S15).
     */
    private fun fallbackGateCoversThisInstall(): Boolean =
        prefs.getLong(StorageKeys.GATE_FALLBACK_INSTALL_TIME, NO_FALLBACK) == firstInstallTime

    /**
     * Whether a deferred check has been started at least once. Set at the start
     * of an attempt; unlike [deferredCheckCompleted] it never gates a retry.
     * Retained so a failed attempt is distinguishable from a never-run one.
     */
    var deferredCheckAttempted: Boolean
        get() = markers.isSet(attemptedMarker)
        set(value) { markers.set(attemptedMarker, value) }

    /**
     * Whether THIS DEVICE has ever completed WarpLink attribution for this app,
     * including under an install that has since been deleted.
     *
     * Kept in SharedPreferences precisely because Auto Backup restores it: the
     * fact has to outlive an uninstall to be able to say "we have seen this
     * device before". It gates nothing. [deferredCheckCompleted] is the gate and
     * must die with its install, so one marker cannot do both jobs.
     */
    val deviceHasCompletedAttribution: Boolean
        get() = prefs.getBoolean(StorageKeys.DEVICE_SEEN_ATTRIBUTION, false)

    /**
     * Whether the install running now is a reinstall: attribution completed on
     * this device before, under an install whose gate is gone.
     *
     * A reinstall still counts as an install and is attributed again. This only
     * tags the attribution request so the two can be told apart afterwards.
     * Read it BEFORE applying a response: completing a check sets the gate,
     * which flips this back to false.
     */
    val isReinstall: Boolean
        get() = deviceHasCompletedAttribution && !deferredCheckCompleted

    var apiKeyValidatedAt: Long?
        get() {
            val value = prefs.getLong(StorageKeys.API_KEY_VALIDATED_AT, -1L)
            return if (value == -1L) null else value
        }
        set(value) {
            val edit = prefs.edit()
            if (value != null) edit.putLong(StorageKeys.API_KEY_VALIDATED_AT, value) else edit.remove(StorageKeys.API_KEY_VALIDATED_AT)
            edit.apply()
        }

    val isApiKeyValidationCacheValid: Boolean
        get() {
            val validatedAt = apiKeyValidatedAt ?: return false
            val elapsed = System.currentTimeMillis() - validatedAt
            return elapsed < VALIDATION_CACHE_DURATION_MS
        }

    /** Custom link domains returned by `/sdk/validate`, cached for offline launches. */
    var cachedDomains: List<String>
        get() = readDomains(prefs)
        set(value) = writeDomains(prefs, value)

    var cachedAttribution: WarpLinkDeepLink?
        get() = readCachedAttribution(prefs)
        set(value) = writeCachedAttribution(prefs, value)

    fun clearCachedAttribution() = prefs.edit().remove(StorageKeys.CACHED_ATTRIBUTION).apply()

    /**
     * Wipe every WarpLink flag from both stores. This models a device that has
     * never run the app, NOT an uninstall: a real uninstall loses the
     * install-scoped markers and keeps the backed-up SharedPreferences.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        markers.clear(completedMarker, attemptedMarker)
        referral.clear()
    }

    /**
     * Forgets this install's attribution so the next check starts fresh: the
     * cached result, both gates, the device-seen flag and the referral records.
     * Keeps the key validation and the link domains, which belong to the key.
     * Retires the referral redeems running now.
     *
     * Returns false when a marker or a preference could not be deleted, so the
     * caller never reports a clear that did not happen.
     */
    fun clearAttribution(): Boolean = synchronized(stateLock) {
        val prefsCleared = prefs.edit()
            .remove(StorageKeys.CACHED_ATTRIBUTION)
            .remove(StorageKeys.GATE_FALLBACK_INSTALL_TIME)
            .remove(StorageKeys.DEVICE_SEEN_ATTRIBUTION)
            .commit()
        val markersCleared = markers.clear(completedMarker, attemptedMarker)
        val referralCleared = referral.clearRecords()
        prefsCleared && markersCleared && referralCleared
    }

    /**
     * Record one definitive attribution outcome in both stores at once.
     *
     * They record the same event with opposite lifetimes, so they are written
     * together: a completion that set only the gate would leave the next
     * reinstall looking like a first install on this device.
     */
    private fun markCheckCompleted() {
        if (!markers.set(completedMarker, true)) {
            prefs.edit().putLong(StorageKeys.GATE_FALLBACK_INSTALL_TIME, firstInstallTime).apply()
        }
        prefs.edit().putBoolean(StorageKeys.DEVICE_SEEN_ATTRIBUTION, true).apply()
    }

    private fun clearGate() {
        markers.set(completedMarker, false)
        prefs.edit().remove(StorageKeys.GATE_FALLBACK_INSTALL_TIME).apply()
    }

    private companion object {
        const val NO_FALLBACK = -1L
        const val VALIDATION_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
