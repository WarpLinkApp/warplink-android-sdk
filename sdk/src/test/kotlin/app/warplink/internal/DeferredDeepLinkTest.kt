package app.warplink.internal

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DeferredDeepLinkTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            "warplink_prefs", Context.MODE_PRIVATE
        ).edit().clear().commit()
        storage = Storage(context)
    }

    @Test
    fun `null referrer reader falls back to fingerprint path`() {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector(context)

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            null, null
        ) { r ->
            result = r
            latch.countDown()
        }

        idleLooperUntilLatch(latch)

        assertTrue(result!!.isFailure)
    }

    @Test
    fun `not first launch returns cached attribution`() {
        storage.isFirstLaunch = false
        storage.cachedAttribution = WarpLinkDeepLink(
            linkId = "link-abc",
            destination = "https://example.com",
            deepLinkUrl = null,
            customParams = emptyMap(),
            isDeferred = true,
            matchType = MatchType.DETERMINISTIC,
            matchConfidence = 1.0
        )

        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector(context)

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            null, null
        ) { r ->
            result = r
        }

        assertTrue(result!!.isSuccess)
        assertEquals("link-abc", result!!.getOrNull()!!.linkId)
    }

    @Test
    fun `not first launch returns null when no cache`() {
        storage.isFirstLaunch = false

        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector(context)

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            null, null
        ) { r ->
            result = r
        }

        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrNull())
    }

    @Test
    fun `referrer reader failure falls back to fingerprint`() {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector(context)
        val referrerReader = InstallReferrerReader(context)

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            referrerReader, null
        ) { r ->
            result = r
            latch.countDown()
        }

        idleLooperUntilLatch(latch)

        // Referrer fails in Robolectric (no Play Store),
        // falls back to fingerprint, then network error
        assertTrue(result!!.isFailure)
    }

    private fun idleLooperUntilLatch(
        latch: CountDownLatch,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        while (latch.count > 0 &&
            System.currentTimeMillis() < deadline
        ) {
            looper.idleFor(Duration.ofMillis(500))
            latch.await(50, TimeUnit.MILLISECONDS)
        }
        looper.idle()
    }

    companion object {
        private const val API_KEY =
            "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    }
}
