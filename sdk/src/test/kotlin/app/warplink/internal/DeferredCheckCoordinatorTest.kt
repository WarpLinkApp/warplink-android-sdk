package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An exact match recorded while the automatic deferred check is on the wire
 * stops that check: callers parked on it get the exact match, and its later
 * answer neither reaches storage nor drains anyone else.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredCheckCoordinatorTest {

    private lateinit var context: Context
    private lateinit var storage: Storage
    private val api = LoopbackJsonServer("""{"matched":false}""")
    private val releaseResponse = CountDownLatch(1)
    private val coordinator = DeferredCheckCoordinator()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        releaseResponse.countDown()
        api.close()
    }

    @Test
    fun `a recorded match answers the callers parked on the check in flight`() {
        val first = onWire()
        var parked: Result<WarpLinkDeepLink?>? = null
        coordinator.check(inputs()) { parked = it }

        coordinator.recordMatch(storage, MATCH)

        assertEquals(MATCH, parked?.getOrNull(), "the parked caller gets the exact match")
        assertEquals(MATCH, storage.cachedAttribution)
        assertTrue(storage.deferredCheckCompleted)

        releaseResponse.countDown()
        idleMainLooperUntil(first.done)

        assertNull(first.result?.getOrNull(), "the stopped check answers only its own caller")
        assertEquals(MATCH, storage.cachedAttribution, "its no-match must not blank the recorded match")
        assertTrue(storage.deferredCheckCompleted)
    }

    @Test
    fun `a recorded match leaves the coordinator free for the next check`() {
        onWire()
        coordinator.recordMatch(storage, MATCH)

        var next: Result<WarpLinkDeepLink?>? = null
        coordinator.check(inputs()) { next = it }

        assertEquals(MATCH, next?.getOrNull(), "a completed check answers from the cache")
    }

    private class Started(val done: CountDownLatch) {
        var result: Result<WarpLinkDeepLink?>? = null
    }

    /** Starts a check and waits until its request is held on the wire. */
    private fun onWire(): Started {
        api.serveOnce(releaseResponse)
        val started = Started(CountDownLatch(1))
        coordinator.check(inputs()) {
            started.result = it
            started.done.countDown()
        }
        assertTrue(api.requestReceived.await(10, TimeUnit.SECONDS), "the check never reached the wire")
        return started
    }

    private fun inputs() = DeferredCheckInputs(
        storage = storage,
        fingerprintCollector = FingerprintCollector(),
        apiClient = ApiClient(API_KEY, api.baseUrl),
        installReferrerReader = InstallReferrerReader(context) { error("no Play Store in a unit test") },
        logger = null,
        settings = RetrySettings.NO_WAIT,
    )

    private companion object {
        const val API_KEY = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
        val MATCH = WarpLinkDeepLink(
            linkId = "lnk_exact",
            destination = "https://example.com/exact",
            isDeferred = true,
        )
    }
}
