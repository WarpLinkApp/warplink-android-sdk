package app.warplink.internal

import android.net.Uri
import android.os.SystemClock
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.advanceBy
import app.warplink.idleMainLooperUntil
import app.warplink.isTimerArmed
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A tap on a weak connection still delivers, and one tap bills one click.
 *
 * Driven at `resolveDeepLink`, which is the funnel both the manual and the
 * automatic entry points share, exactly as ResolveParamsTest drives it.
 */
@RunWith(RobolectricTestRunner::class)
class ResolveRetryTest {

    private val apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    private fun stub(status: Int = 200) =
        LoopbackJsonServer(RESOLVE_JSON, status = status).also { stubs.add(it) }

    /**
     * Resolves [tapped] against [server] and returns the single answer.
     *
     * [drive] runs while the tap is still in flight, handed the latch the answer will
     * fire, for the tests whose subject is a timer rather than a socket. It is empty by
     * default because [RetrySettings.NO_WAIT] leaves nothing to move the clock for: its
     * waits are zero, so each retry is due the moment the one before it fails.
     */
    private fun resolve(
        server: LoopbackJsonServer,
        settings: RetrySettings = RetrySettings.NO_WAIT,
        tapped: String = "https://aplnk.to/abc123",
        drive: (CountDownLatch) -> Unit = {},
    ): Result<WarpLinkDeepLink> {
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink>? = null
        var deliveries = 0
        resolveDeepLink(
            ApiClient(apiKey, server.baseUrl),
            null,
            Uri.parse(tapped),
            settings,
        ) { deliveries++; result = it; done.countDown() }
        drive(done)
        idleMainLooperUntil(done)
        assertEquals(1, deliveries, "one tap must produce exactly one answer")
        return assertNotNull(result)
    }

    /**
     * Release every retry wait the run parks in, until it answers.
     *
     * Three things are load-bearing. Waiting before each jump is what keeps the clock
     * still until the timer exists: a jump taken before the attempt had settled would
     * land the wait FURTHER out than the jump itself, and it would then never come due.
     * Jumping only as far as the longest wait is what keeps the next attempt's own
     * watchdog out of reach, since every per-attempt bound is longer than every wait and
     * the clock stops again the moment the retry runs. And accepting the answer as a
     * reason to stop is what lets one driver serve a run that parks in two waits and a
     * run that parks in one, which HttpURLConnection decides rather than the SDK: it
     * quietly re-sends a request whose connection was closed before any response, so a
     * dropped attempt can cost two requests on the wire and one attempt to the SDK.
     */
    private fun releaseRetryWaits(settings: RetrySettings, done: CountDownLatch) {
        val wait = settings.delaysMs.max() + settings.maxJitterMs
        // A run with no wait parks in no timer, so there is nothing here to release and
        // the loop below would spend its whole hang budget looking for one.
        // RetrySettings.NO_WAIT needs no driver at all: each retry is due the moment the
        // one before it fails, and `idleMainLooperUntil` runs it.
        require(wait > 0) { "settings with no wait must not be driven through here" }
        require(wait < settings.retryTimeoutMs) {
            "a wait longer than an attempt's own bound cannot be told apart from it"
        }
        // maxAttempts bounds the run, so it bounds the waits inside it.
        repeat(settings.maxAttempts) {
            idleMainLooperUntil(
                message = "the run neither answered nor armed a retry wait",
            ) { done.count == 0L || isTimerArmed(notAfterMs = wait) }
            if (done.count == 0L) return
            advanceBy(wait)
        }
    }

    private fun tapIds(server: LoopbackJsonServer): List<String> =
        server.requestHeaders.mapNotNull { headers ->
            headers.firstOrNull { it.startsWith("X-WarpLink-Tap-Id:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        }

    @Test
    fun `WL-S27 a dropped connection then a success delivers one link`() {
        val server = stub()
        server.serveSequence(false, false, true)

        val result = resolve(server)

        assertTrue(server.awaitRequests(3))
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.getOrThrow().linkId)
    }

    @Test
    fun `WL-S27 every attempt of one tap carries the same tap id`() {
        val server = stub()
        server.serveSequence(false, false, true)

        resolve(server)

        val ids = tapIds(server)
        assertEquals(3, ids.size)
        // A fresh id per attempt is the mutation this pins: it would bill three
        // clicks for one tap.
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `WL-S27 three dropped connections surface one NetworkError after exactly three requests`() {
        val server = stub()
        server.serveSequence(false, false, false)

        val result = resolve(server)

        assertEquals(3, server.requestLines.size)
        assertIs<WarpLinkError.NetworkError>(result.exceptionOrNull())
    }

    @Test
    fun `WL-S27 a refusal is answered once and never retried`() {
        for (status in listOf(404, 403, 410)) {
            val server = stub(status = status)
            server.serveSequence(true, true, true)

            resolve(server)

            // A 410 matters as much as the 404: resolveLink's `else ->` branch
            // collapses it into ServerError, so a rule of "retry every
            // ServerError" would retry an expired link.
            assertEquals(1, server.requestLines.size, "status $status must not be retried")
        }
    }

    @Test
    fun `the default settings still finish inside the budget`() {
        val server = stub()
        server.serveSequence(false, false, true)

        // Not NO_WAIT: this one proves the shipped numbers work end to end. The run has
        // to sit through the shipped waits and still have budget left for the attempt
        // that succeeds.
        val result = resolve(server, settings = RetrySettings.DEFAULT) { done ->
            releaseRetryWaits(RetrySettings.DEFAULT, done)
        }

        assertEquals(3, server.requestLines.size)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `WL-S27 an attempt that is accepted and never answered is bounded by its own timeout`() {
        val server = stub()
        // Accepted and never answered, then answered. A DROP fails instantly
        // whatever the timeout is, so only a HANG can put attempt 1's own
        // timeout under test.
        server.serveScript(LoopbackJsonServer.Answer.HANG, LoopbackJsonServer.Answer.RESPOND)
        // No elapsed cap, so the only thing that can end attempt 1 is attempt 1's own
        // bound.
        val settings = RetrySettings.REAL_TIMEOUTS_NO_CAP
        // Measured on elapsedRealtime, the clock BoundedAttempt schedules its watchdog
        // against and the one the shipped timeouts are stated in. Not the wall clock:
        // the bound is a `postDelayed` and the socket it guards is real, so the two run
        // at different speeds here and only one of them is the clock the bound is
        // expressed in. On a device they are the same clock.
        val startedAt = SystemClock.elapsedRealtime()
        val result = resolve(server, settings = settings) { done ->
            // The HANG has to be ON the wire before the clock moves, or the bound would
            // fire against a request that had not been made and the attempt it is
            // supposed to abandon would never have existed.
            assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")
            // The watchdog itself, asserted before it is fired, because firing it is not
            // proof that it exists: `ApiClient` also sets `conn.readTimeout` to this same
            // number, so on a silent connection the socket would end attempt 1 at the
            // same moment whether the watchdog was armed or not. An exact window is safe
            // here because the clock has not moved since the attempt started.
            assertTrue(
                isTimerArmed(
                    notBeforeMs = settings.firstAttemptTimeoutMs.toLong(),
                    notAfterMs = settings.firstAttemptTimeoutMs.toLong(),
                ),
                "attempt 1's watchdog was not armed at ${settings.firstAttemptTimeoutMs}ms",
            )
            // Firing this timer against a request that is still open IS the subject, so
            // the jump is exactly the bound under test: shortening or lengthening
            // firstAttemptTimeoutMs moves the elapsed reading below or above the window
            // asserted at the end.
            advanceBy(settings.firstAttemptTimeoutMs.toLong())
            releaseRetryWaits(settings, done)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        assertEquals(2, server.requestLines.size, "exactly two requests")
        assertTrue(result.isSuccess)
        // Attempt 1 ends at its own 4 second bound, not at ApiClient's 30 second
        // one. Mutating firstAttemptTimeoutMs to 30_000 pushes this past the
        // upper bound rather than merely making the test slow.
        assertTrue(elapsedMs > 3_500, "attempt 1 gave up after ${elapsedMs}ms, too early to be its bound")
        assertTrue(elapsedMs < 8_000, "attempt 1 ran on for ${elapsedMs}ms")
    }

    @Test
    fun `WL-S27 a superseded attempt is disconnected, not left holding its connection`() {
        val server = stub()
        // Neither tap is ever answered, so the only thing that can end attempt
        // 1 early is a disconnect from the newer tap.
        server.serveScript(LoopbackJsonServer.Answer.HANG, LoopbackJsonServer.Answer.HANG)
        // Two minutes per attempt, and no cap. Nothing but the supersede can
        // end attempt 1 inside this test: reaching a two minute bound would
        // take longer to idle through than the deadline below allows, so a
        // green result here can only mean the disconnect landed.
        val settings = RetrySettings(
            delaysMs = listOf(0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 120_000,
            retryTimeoutMs = 120_000,
        )
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink>? = null
        val client = ApiClient(apiKey, server.baseUrl)
        resolveDeepLink(client, null, Uri.parse("https://aplnk.to/abc123"), settings) {
            result = it; done.countDown()
        }
        assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")

        val supersededAt = System.nanoTime()
        resolveDeepLink(client, null, Uri.parse("https://aplnk.to/def456"), settings) { }
        idleMainLooperUntil(done, timeoutMs = 6_000)
        val elapsedMs = (System.nanoTime() - supersededAt) / 1_000_000

        assertIs<WarpLinkError.NetworkError>(assertNotNull(result).exceptionOrNull())
        // Merely ignoring the older attempt would leave it blocked on its own
        // two minute bound, on the connection least able to spare it.
        assertTrue(elapsedMs < 4_000, "the superseded attempt ran on for ${elapsedMs}ms")
    }

    private companion object {
        const val RESOLVE_JSON =
            """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":null,"android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
