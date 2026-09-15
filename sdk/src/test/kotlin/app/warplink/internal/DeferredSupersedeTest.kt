package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a newer deferred check does to the one it replaces: it stops its
 * remaining attempts, and it disconnects the attempt already on the wire.
 *
 * Ignoring the older attempt is not enough. Its connection would stay open for
 * the rest of its read timeout, on the network least able to spare it.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredSupersedeTest {

    private val stubs = mutableListOf<LoopbackJsonServer>()
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `a superseded check stops retrying`() {
        val server = stub(status = 500)
        server.serveSequence(true, true, true)
        var current = true
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null

        performDeferredCheck(
            storage, FingerprintCollector(), ApiClient(API_KEY, server.baseUrl),
            null, null, isCurrentCheck = { current }, settings = RetrySettings.NO_WAIT,
        ) { result = it; done.countDown() }
        assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")
        // Superseded before the looper runs attempt 1's answer, so the decision
        // to retry is taken with this check already abandoned.
        current = false
        idleMainLooperUntil(done)

        assertEquals(1, server.requestLines.size, "an abandoned check kept retrying")
        // It still answers its own caller: swallowing the answer here would
        // strand everyone parked on this check.
        assertIs<WarpLinkError.ServerError>(assertNotNull(result).exceptionOrNull())
    }

    @Test
    fun `a superseded check disconnects the attempt it left on the wire`() {
        val server = stub()
        // Neither check is ever answered, so the only thing that can end the
        // first attempt early is a disconnect from the check that replaced it.
        server.serveScript(LoopbackJsonServer.Answer.HANG, LoopbackJsonServer.Answer.HANG)
        val client = ApiClient(API_KEY, server.baseUrl)
        var current = true
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null

        performDeferredCheck(
            storage, FingerprintCollector(), client,
            null, null, isCurrentCheck = { current }, settings = HELD,
        ) { result = it; done.countDown() }
        assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")

        val supersededAt = System.nanoTime()
        current = false
        performDeferredCheck(
            storage, FingerprintCollector(), client,
            null, null, isCurrentCheck = { true }, settings = HELD,
        ) { }
        idleMainLooperUntil(done, timeoutMs = 6_000)
        val elapsedMs = (System.nanoTime() - supersededAt) / 1_000_000

        assertIs<WarpLinkError.NetworkError>(assertNotNull(result).exceptionOrNull())
        assertTrue(elapsedMs < 4_000, "the superseded attempt ran on for ${elapsedMs}ms")
    }

    private fun stub(body: String = NO_MATCH_JSON, status: Int = 200) =
        LoopbackJsonServer(body, status = status).also { stubs.add(it) }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val NO_MATCH_JSON = """{"matched":false}"""

        /** Long enough that only a disconnect can end an attempt early. */
        val HELD = RetrySettings(
            delaysMs = listOf(0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 8_000,
            retryTimeoutMs = 8_000,
        )
    }
}
