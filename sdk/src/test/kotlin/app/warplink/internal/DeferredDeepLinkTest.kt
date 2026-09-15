package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DeferredDeepLinkTest {

    // The endpoint is a dead port, so the check fails transiently and is retried. The
    // wait before a retry is a `postDelayed` and nothing here moves the clock, so
    // NO_WAIT is what lets the failure these tests are about actually arrive. What the
    // waits are worth is pinned in BoundedRetryTest, against a fake scheduler.

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @Test
    fun `null referrer reader falls back to fingerprint path`() {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector()

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            null, null,
            settings = RetrySettings.NO_WAIT
        ) { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        assertTrue(result!!.isFailure)
    }

    @Test
    fun `completed check returns cached attribution without network`() {
        storage.deferredCheckCompleted = true
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
        val fingerprintCollector = FingerprintCollector()

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
    fun `completed check returns null when no cache`() {
        storage.deferredCheckCompleted = true

        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector()

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
    fun `WL-S06 network failure does not consume the completion flag`() {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector()

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            null, null,
            settings = RetrySettings.NO_WAIT
        ) { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        assertTrue(result!!.isFailure)
        // The attempt was recorded, but completion is NOT consumed on failure —
        // so the next launch retries instead of returning a stale null.
        assertTrue(storage.deferredCheckAttempted)
        assertFalse(storage.deferredCheckCompleted)
    }

    @Test
    fun `referrer reader failure falls back to fingerprint`() {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        val apiClient = ApiClient(API_KEY, "http://localhost:1")
        val fingerprintCollector = FingerprintCollector()
        val referrerReader = InstallReferrerReader(context)

        performDeferredCheck(
            storage, fingerprintCollector, apiClient,
            referrerReader, null,
            settings = RetrySettings.NO_WAIT
        ) { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        // Referrer fails in Robolectric (no Play Store),
        // falls back to fingerprint, then network error
        assertTrue(result!!.isFailure)
    }

    companion object {
        private const val API_KEY =
            "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    }
}
