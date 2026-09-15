package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.WarpLinkDeepLink
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two markers behind `is_reinstall`, which must never collapse into one:
 * the gate dies with its install, the device-seen flag outlives it. A single
 * marker doing both jobs is what makes a platform either double-attribute one
 * install or never re-attribute a reinstall.
 */
@RunWith(RobolectricTestRunner::class)
class StorageReinstallTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
        setInstallTimes(firstInstall = 1_000L, lastUpdate = 1_000L)
    }

    @Test
    fun `a device that never completed a check is not a reinstall`() {
        assertFalse(storage.deviceHasCompletedAttribution)
        assertFalse(storage.isReinstall)
    }

    @Test
    fun `completing the check marks the device seen`() {
        storage.deferredCheckCompleted = true

        assertTrue(storage.deviceHasCompletedAttribution)
        // The gate is still here, so this is the very install that ran the
        // check, not a later one.
        assertFalse(storage.isReinstall)
    }

    @Test
    fun `an install that lost the gate but kept the prefs is a reinstall`() {
        storage.deferredCheckCompleted = true
        simulateReinstall()

        val reinstalled = Storage(context)
        assertTrue(reinstalled.deviceHasCompletedAttribution)
        assertFalse(reinstalled.deferredCheckCompleted)
        assertTrue(reinstalled.isReinstall)
    }

    @Test
    fun `clearing the gate does not un-see the device`() {
        storage.deferredCheckCompleted = true
        storage.deferredCheckCompleted = false

        // Having been attributed once is permanent for the device. Only the
        // per-install gate is clearable, and clearing it cannot rewrite that.
        assertTrue(storage.deviceHasCompletedAttribution)
        assertTrue(storage.isReinstall)
    }

    @Test
    fun `a reinstall does not inherit the previous install's cached match`() {
        storage.deferredCheckCompleted = true
        storage.cachedAttribution = WarpLinkDeepLink(
            linkId = "lnk_old", destination = "https://example.com/old", isDeferred = true
        )

        simulateReinstall()

        // Auto Backup brought the cache back and not the gate. A host reading
        // attributionResult at startup would route to the old campaign.
        assertNull(Storage(context).cachedAttribution)
    }

    @Test
    fun `a closed gate survives reconstruction and keeps its cached match`() {
        storage.deferredCheckCompleted = true
        storage.cachedAttribution = WarpLinkDeepLink(
            linkId = "lnk_current", destination = "https://example.com/current", isDeferred = true
        )

        // No reinstall simulated: this is the same install reopening the app,
        // so the gate it closed is still here and its own cache must survive.
        val reconstructed = Storage(context)
        assertEquals("lnk_current", reconstructed.cachedAttribution?.linkId)
    }

    /**
     * Deleting the app wipes `noBackupFilesDir`; Android Auto Backup then
     * restores SharedPreferences onto the new install. That asymmetry is the
     * entire mechanism, so reproduce it literally rather than stub it.
     */
    private fun simulateReinstall() {
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    /** Storage reads the timestamps once, so set them before constructing it. */
    private fun setInstallTimes(firstInstall: Long, lastUpdate: Long) {
        val info = Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = firstInstall
        info.lastUpdateTime = lastUpdate
    }
}
