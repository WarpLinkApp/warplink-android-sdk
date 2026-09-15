package app.warplink.internal

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.advanceBy
import app.warplink.idleMainLooperUntil
import app.warplink.idleUntilTimerArmed
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which thread the retry runs on, at both ends.
 *
 * A wait has to resume OFF the main looper, because the next attempt does
 * HttpURLConnection I/O and that throws NetworkOnMainThreadException on a real
 * device. The answer has to arrive ON it, because every callback in the API
 * reference says so. Robolectric enforces neither, so both are asserted here.
 *
 * Both retrying paths are covered: the resolve funnel and the deferred check
 * share the hop for the same reason, and one of them losing it is silent.
 */
@RunWith(RobolectricTestRunner::class)
class RetryThreadingTest {

    private val apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `the scheduled wait resumes off the main looper`() {
        val client = ApiClient(apiKey, "http://127.0.0.1:1")
        val ran = CountDownLatch(1)
        var thread: Thread? = null

        client.schedule(0L) { thread = Thread.currentThread(); ran.countDown() }
        idleMainLooperUntil(ran)

        // postDelayed is only the timer. Running the body there would put the
        // next attempt's socket work on the main looper.
        assertEquals(0L, ran.count, "the scheduled block never ran")
        assertNotSame(Looper.getMainLooper().thread, thread)
    }

    @Test
    fun `a tap superseded while it waits still answers on the main thread`() {
        val server = LoopbackJsonServer(RESOLVE_JSON).also { stubs.add(it) }
        // Attempt 1 is accepted and never answered, so it ends at its own
        // 300 ms timeout at a time the test knows.
        server.serveScript(LoopbackJsonServer.Answer.HANG, LoopbackJsonServer.Answer.HANG)
        val settings = RetrySettings(
            delaysMs = listOf(60_000L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 300,
            retryTimeoutMs = 300,
        )
        val done = CountDownLatch(1)
        var thread: Thread? = null

        resolveDeepLink(ApiClient(apiKey, server.baseUrl), null, TAPPED, settings) {
            thread = Thread.currentThread()
            done.countDown()
        }
        // The HANG has to be on the wire before the clock moves, or the bound would
        // fire against a request that had not been made.
        assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")
        // Attempt 1's own bound, and nothing more: it abandons a request nothing else
        // could have ended, which is what parks the run in its wait.
        advanceBy(settings.firstAttemptTimeoutMs.toLong())
        // The abandoned attempt settles on a background thread, so the wait it arms is
        // what says the run reached it. A timer that far out can be nothing else here:
        // every other timer in this test is one 300 ms bound.
        parkedInTheWait(settings)
        assertEquals(1L, done.count, "the run must still be waiting, not answered")

        // A newer tap, while the older one waits.
        resolveDeepLink(ApiClient(apiKey, server.baseUrl), null, NEWER, settings) { }
        assertTrue(server.awaitRequests(2), "the newer tap never reached the wire")
        // One jump, past the wait. Virtual time, so it costs nothing.
        //
        // The only advance in this suite taken while a request is on the wire: it fires
        // the newer tap's own 300 ms bound on the way past. Deliberate and harmless. The
        // newer tap exists to supersede the older one, its callback is empty, and nothing
        // below reads it, so what its abandoned attempt goes on to do is not this test's
        // subject. Waiting it out instead would cost 300 ms of real time to prove nothing.
        advanceBy(61_000)
        idleMainLooperUntil(done)

        assertEquals(0L, done.count, "a superseded tap must still answer its own caller")
        // The wait resumed on the executor, so the superseded branch answers
        // from there. Without a hop back the host would be handed a deep link
        // on a pool thread, against the documented contract.
        assertSame(Looper.getMainLooper().thread, thread)
    }

    @Test
    fun `a deferred check superseded while it waits still answers on the main thread`() {
        val server = LoopbackJsonServer(NO_MATCH_JSON, status = 500).also { stubs.add(it) }
        // One 5xx, then nothing. A 5xx is one connection per attempt and fails
        // at once, so the run is parked in its wait and nowhere else.
        server.serveScript(LoopbackJsonServer.Answer.RESPOND)
        val settings = RetrySettings(
            delaysMs = listOf(60_000L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 300,
            retryTimeoutMs = 300,
        )
        var current = true
        var thread: Thread? = null
        val done = CountDownLatch(1)

        performDeferredCheck(
            Storage(ApplicationProvider.getApplicationContext()), FingerprintCollector(),
            ApiClient(apiKey, server.baseUrl), null, null,
            isCurrentCheck = { current }, settings = settings,
        ) { thread = Thread.currentThread(); done.countDown() }
        // Attempt 1's 5xx lands on a background thread, so the wait it arms is what
        // says the run reached it. A timer that far out can be nothing else here: every
        // other timer in this test is one 300 ms bound.
        parkedInTheWait(settings)
        assertEquals(1L, done.count, "the run must still be waiting, not answered")

        // A newer check, while the older one waits.
        current = false
        // One jump, past the wait. Virtual time, so it costs nothing.
        advanceBy(61_000)
        idleMainLooperUntil(done)

        assertEquals(0L, done.count, "a superseded check must still answer its own caller")
        // The wait resumed on the executor, so the superseded branch answers
        // from there. Without a hop back the host would be handed its
        // attribution result on a pool thread, against the documented contract.
        assertSame(Looper.getMainLooper().thread, thread)
    }

    /**
     * Wait, in real time, for the run to reach the wait it is about to be superseded in.
     *
     * The wait is the only thing these two tests can be superseded IN, so the assertion
     * that follows is worth nothing until it is armed. Waiting for it also has to happen
     * before the jump: a jump taken first would land the wait further out than the jump
     * itself, and it would then never come due.
     *
     * The deliberately outsized [RetrySettings.delaysMs] is what makes it recognisable.
     * Every other timer in these tests is one 300 ms attempt bound, so a timer a whole
     * minute out is the wait and nothing else.
     */
    private fun parkedInTheWait(settings: RetrySettings) {
        val wait = settings.delaysMs.first()
        require(wait > settings.retryTimeoutMs) {
            "a wait shorter than an attempt's own bound cannot be told apart from it"
        }
        idleUntilTimerArmed(
            notBeforeMs = wait,
            message = "the run never reached its wait",
        )
    }

    private companion object {
        val TAPPED: Uri = Uri.parse("https://aplnk.to/abc123")
        val NEWER: Uri = Uri.parse("https://aplnk.to/def456")

        const val NO_MATCH_JSON = """{"matched":false}"""

        const val RESOLVE_JSON =
            """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":null,"android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
