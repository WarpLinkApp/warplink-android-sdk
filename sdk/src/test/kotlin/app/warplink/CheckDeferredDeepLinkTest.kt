package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CheckDeferredDeepLinkTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context

    @Before
    fun setUp() {
        WarpLink.reset()
        // Every endpoint here is a dead port, so the check fails transiently and is
        // retried. The wait before a retry is a `postDelayed`, and nothing below moves
        // the clock, so without this the answer these tests are about would sit behind
        // a timer that never comes due. What the waits are worth is pinned in
        // BoundedRetryTest, against a fake scheduler and a fake clock.
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
    }

    @After
    fun tearDown() {
        // WarpLink is an object, so the override is process-wide and outlives this
        // class inside one Gradle test JVM. reset() clears it.
        WarpLink.reset()
    }

    @Test
    fun testNotConfiguredReturnsError() {
        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NotConfigured>(result!!.exceptionOrNull())
    }

    @Test
    fun testFirstLaunchNetworkErrorPropagates() {
        configureManual()

        var result: Result<WarpLinkDeepLink?>? = null
        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun testNetworkErrorLeavesCheckIncompleteForRetry() {
        configureManual()

        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { latch.countDown() }

        idleMainLooperUntil(latch)

        val storage = Storage(context)
        // Offline first launch: attempted, but NOT completed -> retries next launch.
        assertTrue(storage.deferredCheckAttempted)
        assertFalse(storage.deferredCheckCompleted)
    }

    @Test
    fun testSecondCallerCoalescesOntoInFlightCheck() {
        configureManual()

        val results = CopyOnWriteArrayList<Result<WarpLinkDeepLink?>>()
        val latch = CountDownLatch(2)
        WarpLink.checkDeferredDeepLink { r -> results.add(r); latch.countDown() }
        // Arrives while the first check is still running: it must receive that
        // check's real result, not an empty first-launch cache.
        WarpLink.checkDeferredDeepLink { r -> results.add(r); latch.countDown() }

        idleMainLooperUntil(latch)

        assertEquals(2, results.size)
        assertIs<WarpLinkError.NetworkError>(results[0].exceptionOrNull())
        assertIs<WarpLinkError.NetworkError>(results[1].exceptionOrNull())
    }

    @Test
    fun testManualCheckStillRunsWhenAutoHasNoSink() {
        // Opt-out defaults with no onLink: the auto check still fires, since a
        // bare configure is documented to attribute automatically. This manual
        // caller must therefore be coalesced onto the in-flight check and get
        // its real result rather than an empty first-launch cache.
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false
            )
        )

        var result: Result<WarpLinkDeepLink?>? = null
        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun testAbandonedCheckDoesNotAnswerTheReplacementsCallers() {
        val first = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
        val second = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
        // Two auto-fired checks: the reconfigure abandons the first one, which
        // must not clear the second's in-flight state when its response lands.
        configureAutoDeferred(first)
        configureAutoDeferred(second)

        var parked: Result<WarpLinkDeepLink?>? = null
        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { r ->
            parked = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        assertTrue(second.isNotEmpty())
        // The parked caller must get the live check's result, not the abandoned
        // one's, which the reconfigure already answered separately.
        assertSame(
            second.first().exceptionOrNull(),
            parked!!.exceptionOrNull()
        )
    }

    @Test
    fun testCompletedCheckReturnsCachedMatch() {
        markCompletedWithCache(CACHED_ATTRIBUTION_JSON)
        configureManual()

        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }

        assertTrue(result!!.isSuccess)
        val deepLink = result!!.getOrNull()!!
        assertEquals("link_cached", deepLink.linkId)
        assertEquals("https://example.com/cached", deepLink.destination)
        assertEquals("myapp://cached", deepLink.deepLinkUrl)
        assertEquals(mapOf("source" to "test"), deepLink.customParams)
        assertTrue(deepLink.isDeferred)
        assertEquals(MatchType.PROBABILISTIC, deepLink.matchType)
        assertEquals(0.85, deepLink.matchConfidence)
    }

    @Test
    fun testCompletedCheckReturnsNullWhenNoCache() {
        Storage(context).deferredCheckCompleted = true
        configureManual()

        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }

        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrNull())
    }

    @Test
    fun testCachedResultSurvivesReread() {
        markCompletedWithCache(CACHED_ATTRIBUTION_JSON)
        configureManual()

        var result1: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result1 = r }

        var result2: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result2 = r }

        val dl1 = result1!!.getOrNull()!!
        val dl2 = result2!!.getOrNull()!!
        assertEquals(dl1.linkId, dl2.linkId)
        assertEquals(dl1.destination, dl2.destination)
        assertEquals(dl1.deepLinkUrl, dl2.deepLinkUrl)
        assertEquals(dl1.matchType, dl2.matchType)
        assertEquals(dl1.matchConfidence, dl2.matchConfidence)
    }

    // Fully manual config: no auto cold-start registration and no auto-fired
    // deferred check, so each test drives checkDeferredDeepLink in isolation.
    private fun configureManual() {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = false
            )
        )
    }

    // Auto-fired deferred check with its own onLink sink, so each configure's
    // result is distinguishable from the other's.
    private fun configureAutoDeferred(
        sink: CopyOnWriteArrayList<Result<WarpLinkDeepLink>>
    ) {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = true,
                onLink = { result -> sink.add(result) }
            )
        )
    }

    private fun markCompletedWithCache(json: String) {
        Storage(context).deferredCheckCompleted = true
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().putString("cached_attribution", json).commit()
    }

    companion object {
        private val CACHED_ATTRIBUTION_JSON = """
            {
                "linkId": "link_cached",
                "destination": "https://example.com/cached",
                "deepLinkUrl": "myapp://cached",
                "customParams": {"source": "test"},
                "isDeferred": true,
                "matchType": "probabilistic",
                "matchConfidence": 0.85
            }
        """.trimIndent()
    }
}
