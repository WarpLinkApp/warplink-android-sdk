package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A device that upgrades in place from 1.0.x must still be attributed.
 *
 * 1.0.x set `is_first_launch = false` at the START of the deferred check, before
 * a single byte left the device. The flag therefore proves only that a check
 * BEGAN, never that an install was recorded. Reading it as "already attributed"
 * closes a gate that lives in `noBackupFilesDir`, which nothing short of a
 * reinstall clears, so the upgrade is locked out of attribution permanently.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyUpgradeAttributionTest {

    private lateinit var context: Context
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `an in-place 1_0_x upgrade still sends an attribution request`() {
        upgradeInPlaceFrom102()

        val stub = stub()
        stub.serveOnce()
        val done = CountDownLatch(1)
        performDeferredCheck(
            Storage(context), FingerprintCollector(),
            ApiClient(API_KEY, stub.baseUrl), null, null
        ) { done.countDown() }
        idleMainLooperUntil(done)

        // The product outcome, asserted on the wire rather than on a flag: an
        // upgrading install that never asks can never be attributed.
        assertTrue(
            stub.requestReceived.await(1, TimeUnit.SECONDS),
            "no attribution request left the device on the upgrade launch"
        )
    }

    @Test
    fun `a consumed legacy flag does not close the gate`() {
        upgradeInPlaceFrom102()

        assertFalse(Storage(context).deferredCheckCompleted)
    }

    @Test
    fun `reading the gate does not also mark the device seen`() {
        upgradeInPlaceFrom102()
        val storage = Storage(context)
        // Reading the gate is what every launch does first (performDeferredCheck
        // line 23), and it is the read that has the side effect.
        storage.deferredCheckCompleted

        // 1.0.x recorded no install for this device, so there is no earlier
        // attribution to have been part of. Writing the device-seen pref here
        // backdates one, and that pref is restored by Auto Backup, so it
        // follows the user onto their next phone.
        assertFalse(storage.deviceHasCompletedAttribution)
    }

    @Test
    fun `clearing the legacy flag cannot reopen the gate`() {
        upgradeInPlaceFrom102()
        // One read is all it takes: the decision is written to durable storage.
        Storage(context).deferredCheckCompleted

        clearLegacyFlag()

        // The marker lands in noBackupFilesDir, which no launch clears. Removing
        // the flag it was derived from cannot undo it, so a lockout outlives its
        // own cause and no later release can recover the install.
        assertFalse(Storage(context).deferredCheckCompleted)
    }

    /**
     * A device that ran 1.0.2, then took an in-place update to this version.
     * Android moves `lastUpdateTime` past `firstInstallTime` on an update only,
     * and Storage reads both once, at construction, so set them before building
     * the instance under test.
     */
    private fun upgradeInPlaceFrom102() {
        legacyPrefs().edit().putBoolean(LEGACY_FLAG, false).commit()
        val info = Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = 1_000L
        info.lastUpdateTime = 2_000L
    }

    private fun clearLegacyFlag() {
        legacyPrefs().edit().remove(LEGACY_FLAG).commit()
    }

    private fun legacyPrefs() =
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)

    private fun stub(): LoopbackJsonServer =
        LoopbackJsonServer(NO_MATCH_JSON).also { stubs.add(it) }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val NO_MATCH_JSON = """{"matched":false}"""
        const val LEGACY_FLAG = "is_first_launch"
    }
}
