package app.warplink

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WarpLinkTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val testKey = "wl_test_abcdefghijklmnopqrstuvwxyz012345"

    // Disable automatic handling so these tests exercise the plumbing directly
    // without background auto-fires; the opt-out defaults are covered elsewhere.
    private fun manualOptions(endpoint: String = "http://localhost:1") =
        WarpLinkOptions(
            apiEndpoint = endpoint,
            automaticDeepLinks = false,
            automaticDeferredDeepLinks = false
        )

    @Before
    fun setUp() {
        WarpLink.reset()
        // The endpoint is a dead port, so a resolve fails transiently and is retried.
        // The wait before a retry is a `postDelayed`, and nothing below moves the
        // clock, so without this the NetworkError one test reads as proof would sit
        // behind a timer that never comes due. What the waits are worth is pinned in
        // BoundedRetryTest, against a fake scheduler.
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        Storage(ApplicationProvider.getApplicationContext()).clearAll()
    }

    @After
    fun tearDown() {
        // WarpLink is an object, so the override is process-wide and outlives this
        // class inside one Gradle test JVM. reset() clears it.
        WarpLink.reset()
    }

    @Test
    fun `isConfigured returns false before configure`() {
        assertFalse(WarpLink.isConfigured)
    }

    @Test
    fun `isConfigured returns true after configure with valid key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `configure accepts wl_test_ prefix`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            testKey,
            manualOptions()
        )
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `configure does not throw and stays unconfigured for empty key`() {
        assertConfigureRejects("")
    }

    @Test
    fun `configure does not throw and stays unconfigured for missing prefix`() {
        assertConfigureRejects("abcdefghijklmnopqrstuvwxyz01234567890")
    }

    @Test
    fun `configure does not throw and stays unconfigured for wrong prefix`() {
        assertConfigureRejects("wl_prod_abcdefghijklmnopqrstuvwxyz012345")
    }

    @Test
    fun `configure does not throw and stays unconfigured for too short key`() {
        assertConfigureRejects("wl_live_abc")
    }

    @Test
    fun `configure does not throw and stays unconfigured for too long key`() {
        assertConfigureRejects("wl_live_abcdefghijklmnopqrstuvwxyz0123456")
    }

    @Test
    fun `configure does not throw and stays unconfigured for special chars`() {
        assertConfigureRejects("wl_live_abcdefghijklmnopqrstuvwxyz01234!")
    }

    @Test
    fun `bad key dispatches InvalidApiKeyFormat to onLink`() {
        var error: Throwable? = null
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "not-a-key",
            WarpLinkOptions(onLink = { result -> error = result.exceptionOrNull() })
        )
        assertFalse(WarpLink.isConfigured)
        assertIs<WarpLinkError.InvalidApiKeyFormat>(error)
    }

    @Test
    fun `WL-S11 bad key warns to Logcat even with debug logging off`() {
        ShadowLog.clear()
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "not-a-key",
            manualOptions()
        )
        // Documented behaviour: a bare configure with a typo'd key must be
        // visible in Logcat, so the warning is not gated on debugLogging.
        val warnings = ShadowLog.getLogsForTag("WarpLink")
            .filter { it.type == Log.WARN }
        assertTrue(warnings.any { it.msg.contains("Invalid API key format") })
    }

    @Test
    fun `handleDeepLink before configure returns NotConfigured`() {
        var result: Result<WarpLinkDeepLink>? = null
        WarpLink.handleDeepLink(
            Uri.parse("https://aplnk.to/test")
        ) { r -> result = r }
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NotConfigured>(
            result!!.exceptionOrNull()
        )
    }

    @Test
    fun `checkDeferredDeepLink before configure returns NotConfigured`() {
        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NotConfigured>(
            result!!.exceptionOrNull()
        )
    }

    @Test
    fun `onNewIntent before configure is a no-op`() {
        // No onLink sink, no autoHandler; must not throw.
        WarpLink.onNewIntent(android.content.Intent().apply {
            data = Uri.parse("https://aplnk.to/abc123")
        })
        assertFalse(WarpLink.isConfigured)
    }

    @Test
    fun `reset sets isConfigured back to false`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )
        assertTrue(WarpLink.isConfigured)
        WarpLink.reset()
        assertFalse(WarpLink.isConfigured)
    }

    @Test
    fun `configure completes without throwing on network error`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )
        assertTrue(WarpLink.isConfigured)

        val latch = CountDownLatch(1)
        latch.await(2, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `configure skips validation when cache is valid`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences(
            "warplink_prefs", Context.MODE_PRIVATE
        )
        prefs.edit()
            .putLong("api_key_validated_at", System.currentTimeMillis())
            .commit()

        WarpLink.configure(ctx, validKey, manualOptions())
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `reconfigure resets and completes successfully`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        WarpLink.configure(ctx, validKey, manualOptions())
        assertTrue(WarpLink.isConfigured)

        WarpLink.configure(ctx, testKey, manualOptions())
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `handleDeepLink with unknown domain returns InvalidUrl`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )

        var result: Result<WarpLinkDeepLink>? = null
        WarpLink.handleDeepLink(
            Uri.parse("https://example.com/foo")
        ) { r -> result = r }

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.InvalidUrl>(
            result!!.exceptionOrNull()
        )
    }

    @Test
    fun `handleDeepLink with missing slug returns InvalidUrl`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )

        var result: Result<WarpLinkDeepLink>? = null
        WarpLink.handleDeepLink(
            Uri.parse("https://aplnk.to/")
        ) { r -> result = r }

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.InvalidUrl>(
            result!!.exceptionOrNull()
        )
    }

    @Test
    fun `handleDeepLink with bare domain returns InvalidUrl`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )

        var result: Result<WarpLinkDeepLink>? = null
        WarpLink.handleDeepLink(
            Uri.parse("https://aplnk.to")
        ) { r -> result = r }

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.InvalidUrl>(
            result!!.exceptionOrNull()
        )
    }

    @Test
    fun `handleDeepLink valid URI reaches API and gets NetworkError`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )

        var result: Result<WarpLinkDeepLink>? = null
        val latch = CountDownLatch(1)
        WarpLink.handleDeepLink(
            Uri.parse("https://aplnk.to/abc123")
        ) { r ->
            result = r
            latch.countDown()
        }

        // Three bounded attempts, each answered from a background thread through the
        // main handler, so the looper has to be run until the last of them lands
        // rather than idled once.
        idleMainLooperUntil(latch)

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(
            result!!.exceptionOrNull()
        )
    }

    private fun assertConfigureRejects(key: String) {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            key,
            manualOptions()
        )
        assertFalse(WarpLink.isConfigured)
    }
}
