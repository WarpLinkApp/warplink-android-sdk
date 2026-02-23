package app.warplink

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WarpLinkTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val testKey = "wl_test_abcdefghijklmnopqrstuvwxyz012345"

    @Before
    fun setUp() {
        WarpLink.reset()
        clearPrefs()
    }

    @Test
    fun `isConfigured returns false before configure`() {
        assertFalse(WarpLink.isConfigured)
    }

    @Test
    fun `isConfigured returns true after configure with valid key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey
        )
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `configure accepts wl_test_ prefix`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            testKey
        )
        assertTrue(WarpLink.isConfigured)
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for empty key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(), ""
        )
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for missing prefix`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "abcdefghijklmnopqrstuvwxyz01234567890"
        )
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for wrong prefix`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "wl_prod_abcdefghijklmnopqrstuvwxyz012345"
        )
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for too short key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "wl_live_abc"
        )
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for too long key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "wl_live_abcdefghijklmnopqrstuvwxyz0123456"
        )
    }

    @Test(expected = WarpLinkError.InvalidApiKeyFormat::class)
    fun `configure throws for special chars in key`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            "wl_live_abcdefghijklmnopqrstuvwxyz01234!"
        )
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
    fun `reset sets isConfigured back to false`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey
        )
        assertTrue(WarpLink.isConfigured)
        WarpLink.reset()
        assertFalse(WarpLink.isConfigured)
    }

    @Test
    fun `configure completes without throwing on network error`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey
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

        WarpLink.configure(ctx, validKey)
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `reconfigure resets and completes successfully`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        WarpLink.configure(ctx, validKey)
        assertTrue(WarpLink.isConfigured)

        WarpLink.configure(ctx, testKey)
        assertTrue(WarpLink.isConfigured)
    }

    @Test
    fun `handleDeepLink with unknown domain returns InvalidUrl`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            WarpLinkOptions(apiEndpoint = "http://localhost:1")
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
            WarpLinkOptions(apiEndpoint = "http://localhost:1")
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
            WarpLinkOptions(apiEndpoint = "http://localhost:1")
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
            WarpLinkOptions(apiEndpoint = "http://localhost:1")
        )

        var result: Result<WarpLinkDeepLink>? = null
        val latch = CountDownLatch(1)
        WarpLink.handleDeepLink(
            Uri.parse("https://aplnk.to/abc123")
        ) { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(
            result!!.exceptionOrNull()
        )
    }

    private fun clearPrefs() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
