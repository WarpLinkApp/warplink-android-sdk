package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.performDeferredCheck
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what a deferred check that a reconfigure (or reset) abandoned mid-flight
 * may do when its `/attribution/match` response finally lands: nothing to
 * storage, and nothing to the host's `onLink`.
 */
@RunWith(RobolectricTestRunner::class)
class AbandonedDeferredCheckTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private lateinit var storage: Storage
    private val api = LoopbackJsonServer(NO_MATCH_JSON)
    private val releaseResponse = CountDownLatch(1)

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        releaseResponse.countDown()
        api.close()
        // WarpLink is an object, so the override WL-S07 sets is process-wide and
        // outlives this class inside one Gradle test JVM. reset() clears it.
        WarpLink.reset()
    }

    @Test
    fun `an abandoned no-match does not blank the replacement's cached match`() {
        api.serveOnce(gate = releaseResponse)

        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        performDeferredCheck(
            storage, FingerprintCollector(), apiClient(),
            null, null,
            isCurrentCheck = { false }
        ) { r ->
            result = r
            done.countDown()
        }

        assertTrue(api.requestReceived.await(10, TimeUnit.SECONDS))
        // While this check was in flight a reconfigure abandoned it, and the
        // replacement found the install this one created and cached a real match.
        storage.cachedAttribution = MATCH
        storage.deferredCheckCompleted = true
        releaseResponse.countDown()

        idleMainLooperUntil(done)

        // The stale no-match must not reach storage.
        assertEquals("lnk_real", storage.cachedAttribution?.linkId)
        assertTrue(storage.deferredCheckCompleted)
        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrNull())
    }

    @Test
    fun `a live no-match still clears a stale cache`() {
        api.serveOnce()
        storage.cachedAttribution = MATCH

        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        performDeferredCheck(
            storage, FingerprintCollector(), apiClient(),
            null, null,
            isCurrentCheck = { true }
        ) { r ->
            result = r
            done.countDown()
        }

        idleMainLooperUntil(done)

        // Control for the test above: the generation guard is what protects the
        // cache, not a blanket refusal to apply a no-match.
        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrNull())
        assertNull(storage.cachedAttribution)
        assertTrue(storage.deferredCheckCompleted)
    }

    @Test
    fun `WL-S07 an abandoned auto check does not dispatch to the superseded onLink`() {
        // Both configures point at a dead port, so each check fails transiently and
        // is retried. The wait before a retry is a `postDelayed` and this test never
        // moves the clock, so without this the second sink's dispatch would sit behind
        // a timer that never comes due.
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        val first = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
        val second = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
        configureAutoDeferred(first)
        configureAutoDeferred(second)

        val done = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { done.countDown() }
        idleMainLooperUntil(done)

        // The reconfigure abandoned the first check, so its result must not reach
        // the sink being replaced: a match there would navigate twice for one
        // install, and a failure is noise at a host that merely reconfigured.
        assertTrue(first.isEmpty())
        assertTrue(second.isNotEmpty())
    }

    @Test
    fun `a caller parked on an abandoned check is answered with success(null), as on iOS`() {
        val gate = CountDownLatch(1)
        val held = LoopbackJsonServer(NO_MATCH_JSON)
        held.serveOnce(gate)
        configureAgainst(held.baseUrl)                  // the auto check is in flight, held by the gate
        var answer: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { answer = it }   // parked on that check

        configureAgainst(held.baseUrl)                  // abandons it; parked callers are answered inline
        gate.countDown()
        held.close()

        // The SDK is configured. NotConfigured was a fabricated error, and the
        // same React Native call rejected on Android and resolved on iOS.
        assertTrue(answer!!.isSuccess, "got ${answer!!.exceptionOrNull()}")
        assertNull(answer!!.getOrNull())
    }

    private fun configureAgainst(baseUrl: String) {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = baseUrl,
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = true,
                onLink = { }
            )
        )
    }

    private fun apiClient() = ApiClient(validKey, api.baseUrl)

    // Auto-fired deferred check with its own onLink sink, so each configure's
    // dispatches are distinguishable from the other's.
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

    companion object {
        private const val NO_MATCH_JSON = """{"matched":false}"""
        private val MATCH = WarpLinkDeepLink(
            linkId = "lnk_real",
            destination = "https://example.com/real",
            isDeferred = true
        )
    }
}
