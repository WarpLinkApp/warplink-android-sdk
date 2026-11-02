package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.WarpLinkDeepLink
import app.warplink.internal.referral.PendingRedemption
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test and Production device state live apart. A `wl_test_` build and a live
 * build on one device must never read, write or clear each other's.
 */
@RunWith(RobolectricTestRunner::class)
class StorageEnvironmentTest {

    private lateinit var context: Context
    private lateinit var live: Storage
    private lateinit var test: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        live = Storage(context, KeyEnvironment.PRODUCTION)
        test = Storage(context, KeyEnvironment.TEST)
        live.clearAll()
        test.clearAll()
    }

    @Test
    fun `only a wl_test_ key is the Test environment`() {
        assertEquals(KeyEnvironment.TEST, KeyEnvironment.of("wl_test_abcdefghijklmnopqrstuvwxyz012345"))
        assertEquals(KeyEnvironment.PRODUCTION, KeyEnvironment.of("wl_live_abcdefghijklmnopqrstuvwxyz012345"))
        assertEquals(KeyEnvironment.PRODUCTION, KeyEnvironment.of("wl_other_abcdefghijklmnopqrstuvwxyz012345"))
    }

    @Test
    fun `Production keeps the names 1_2_0 wrote, so live state survives the upgrade`() {
        val prefs = context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("device_seen_attribution", true)
            .putLong("api_key_validated_at", 42L)
            .putString("cached_domains", """["go.example.com"]""")
            .commit()
        File(context.noBackupFilesDir, "warplink_deferred_completed").createNewFile()
        File(context.noBackupFilesDir, "warplink_deferred_attempted").createNewFile()

        val upgraded = Storage(context)

        assertTrue(upgraded.deferredCheckCompleted)
        assertTrue(upgraded.deferredCheckAttempted)
        assertTrue(upgraded.deviceHasCompletedAttribution)
        assertEquals(42L, upgraded.apiKeyValidatedAt)
        assertEquals(listOf("go.example.com"), upgraded.cachedDomains)
        assertFalse(Storage(context, KeyEnvironment.TEST).deferredCheckCompleted)
    }

    @Test
    fun `Test state is written under its own names`() {
        test.deferredCheckCompleted = true
        test.deferredCheckAttempted = true

        assertTrue(File(context.noBackupFilesDir, "warplink_deferred_completed_test").exists())
        assertTrue(File(context.noBackupFilesDir, "warplink_deferred_attempted_test").exists())
        assertFalse(File(context.noBackupFilesDir, "warplink_deferred_completed").exists())
        assertFalse(File(context.noBackupFilesDir, "warplink_deferred_attempted").exists())
        assertTrue(context.getSharedPreferences("warplink_prefs_test", Context.MODE_PRIVATE).getBoolean("device_seen_attribution", false))
        assertFalse(context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE).getBoolean("device_seen_attribution", false))
    }

    @Test
    fun `Test completion, cache, validation and domains never reach live`() {
        test.deferredCheckCompleted = true
        test.deferredCheckAttempted = true
        test.cachedAttribution = LINK
        test.apiKeyValidatedAt = System.currentTimeMillis()
        test.cachedDomains = listOf("test.example.com")

        assertFalse(live.deferredCheckCompleted)
        assertFalse(live.deferredCheckAttempted)
        assertFalse(live.deviceHasCompletedAttribution)
        assertNull(live.cachedAttribution)
        assertNull(live.apiKeyValidatedAt)
        assertTrue(live.cachedDomains.isEmpty())
    }

    @Test
    fun `live completion and cache never reach Test`() {
        live.deferredCheckCompleted = true
        live.cachedAttribution = LINK

        assertFalse(test.deferredCheckCompleted)
        assertNull(test.cachedAttribution)
    }

    @Test
    fun `referral records of the two environments are separate files`() {
        test.referral.pending.save(PendingRedemption("test-token", 9_999_999_999L))
        live.referral.pending.save(PendingRedemption("live-token", 9_999_999_999L))

        assertEquals("test-token", test.referral.pending.load()?.token)
        assertEquals("live-token", live.referral.pending.load()?.token)
        assertTrue(File(context.noBackupFilesDir, "warplink_referral_pending_test.json").exists())
        assertTrue(File(context.noBackupFilesDir, "warplink_referral_pending.json").exists())
    }

    @Test
    fun `clearing Test attribution empties the whole Test set and leaves live alone`() {
        fillAll(test)
        fillAll(live)

        assertTrue(test.clearAttribution())

        assertFalse(test.deferredCheckCompleted)
        assertFalse(test.deferredCheckAttempted)
        assertFalse(test.deviceHasCompletedAttribution)
        assertNull(test.cachedAttribution)
        assertNull(test.referral.pending.load())
        assertNull(test.referral.evidence.evidence().installId)
        assertNull(test.referral.referrerTap.heldTapId("tap-token"))
        assertTrue(live.deferredCheckCompleted)
        assertTrue(live.deferredCheckAttempted)
        assertNotNull(live.cachedAttribution)
        assertNotNull(live.referral.pending.load())
    }

    @Test
    fun `clearing attribution keeps the key validation and link domains`() {
        fillAll(test)

        test.clearAttribution()

        assertNotNull(test.apiKeyValidatedAt)
        assertEquals(listOf("test.example.com"), test.cachedDomains)
    }

    @Test
    fun `clearing attribution reports failure when a marker cannot be deleted`() {
        test.deferredCheckCompleted = true
        // A non-empty directory in the marker's place cannot be deleted as a file.
        val stuck = File(context.noBackupFilesDir, "warplink_deferred_attempted_test")
        stuck.mkdirs()
        File(stuck, "child").createNewFile()

        assertFalse(test.clearAttribution())
        assertFalse(test.deferredCheckCompleted)
    }

    @Test
    fun `clearing attribution retires the redeems running now`() {
        val before = test.referral.epoch

        test.clearAttribution()

        assertEquals(before + 1, test.referral.epoch)
    }

    private fun fillAll(storage: Storage) {
        storage.deferredCheckCompleted = true
        storage.deferredCheckAttempted = true
        storage.cachedAttribution = LINK
        storage.apiKeyValidatedAt = System.currentTimeMillis()
        storage.cachedDomains = listOf("test.example.com")
        storage.referral.pending.save(PendingRedemption("pending-token", 9_999_999_999L))
        storage.referral.evidence.rememberInstall("install-1")
        storage.referral.referrerTap.tapIdFor("tap-token")
    }

    private companion object {
        val LINK = WarpLinkDeepLink(
            linkId = "lnk_1",
            destination = "https://example.com",
            isDeferred = true,
        )
    }
}
