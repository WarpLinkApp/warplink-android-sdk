package app.warplink.internal

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.advanceBy
import app.warplink.idleMainLooperUntil
import app.warplink.isTimerArmed
import org.json.JSONException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An attempt is bounded in elapsed time, not merely between bytes.
 *
 * `readTimeout` is SO_TIMEOUT: it bounds ONE read, so a server answering one
 * byte every half second resets it for ever and the attempt never ends. The
 * elapsed cap does not save it either, because that is only consulted BETWEEN
 * attempts. Without a bound on the attempt itself the twelve second worst case
 * held for a silent connection and not for a slow one, and both are the same
 * weak uplink.
 */
@RunWith(RobolectricTestRunner::class)
class DrippingAttemptTest {

    private val apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `WL-S27 an attempt that keeps receiving bytes is still abandoned at its own timeout`() {
        val server = stub()
        // Answered one byte at a time, then answered properly. A DROP fails
        // instantly and a HANG is silent; only a DRIP is a RUNNING attempt, so
        // only a DRIP puts a bound on the attempt itself under test.
        server.serveScript(
            LoopbackJsonServer.Answer.DRIP,
            LoopbackJsonServer.Answer.RESPOND,
        )
        val settings = RetrySettings(
            delaysMs = listOf(0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 4_000,
            retryTimeoutMs = 4_000,
        )

        // Measured on `elapsedRealtime`, which is the clock the watchdog is scheduled
        // against and the one the bound is stated in. Not the wall clock: the socket is
        // real and the timer is not, so the two run at different speeds here. On a
        // device they are the same clock.
        val startedAt = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink>? = null
        var deliveries = 0
        resolveDeepLink(
            ApiClient(apiKey, server.baseUrl),
            null,
            Uri.parse("https://aplnk.to/abc123"),
            settings,
        ) { deliveries++; result = it; done.countDown() }
        // The drip has to be ON the wire before the clock moves, or the bound would
        // fire against a request that had not been made and the running attempt it is
        // supposed to abandon would never have existed.
        assertTrue(server.awaitRequests(1), "attempt 1 never reached the wire")
        // The watchdog itself, asserted before it is fired. The drip below is already
        // proof that only the watchdog can end this attempt, so this is the narrower
        // claim rather than a second one: it says the bound is armed where the settings
        // say, not merely that something ended the attempt. An exact window is safe here
        // because the clock has not moved since the attempt started.
        assertTrue(
            isTimerArmed(
                notBeforeMs = settings.firstAttemptTimeoutMs.toLong(),
                notAfterMs = settings.firstAttemptTimeoutMs.toLong(),
            ),
            "attempt 1's watchdog was not armed at ${settings.firstAttemptTimeoutMs}ms",
        )
        // Firing this timer against an attempt that is still receiving bytes IS the
        // subject, so the jump is exactly the bound under test: mutating
        // firstAttemptTimeoutMs moves the elapsed reading out of the window asserted
        // below. Attempt 2 needs no jump of its own, since this run's wait is zero.
        advanceBy(settings.firstAttemptTimeoutMs.toLong())
        idleMainLooperUntil(done)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        assertEquals(1, deliveries, "one tap must produce exactly one answer")
        assertTrue(assertNotNull(result).isSuccess, "attempt 2 must deliver the link")
        assertEquals(2, server.requestLines.size, "exactly two requests")
        // Attempt 1 was receiving a byte every half second throughout, so
        // nothing between bytes could end it. Only its own bound does, at about
        // four seconds.
        assertTrue(elapsedMs > 3_500, "attempt 1 gave up after ${elapsedMs}ms, too early to be its bound")
        assertTrue(elapsedMs < 8_000, "attempt 1 ran on for ${elapsedMs}ms")
    }

    @Test
    fun `WL-S27 an abandoned attempt is reported as a timeout, not as a decoding error`() {
        // The watchdog cuts a response in half, and a half-read body raises a
        // JSONException, which ApiClient maps to DecodingError. isRetryable
        // refuses that, so the bound would END the run instead of buying the
        // retry it exists to buy.
        val attempt = firedWatchdog()
        val halfRead = Result.failure<String>(
            WarpLinkError.DecodingError(JSONException("Unterminated object"))
        )

        val settled = attempt.settle(halfRead)

        val error = assertNotNull(settled.exceptionOrNull())
        assertIs<WarpLinkError.NetworkError>(error)
        assertTrue(isRetryable(error), "an abandoned attempt must earn its retry")
    }

    @Test
    fun `an answer that arrives as the watchdog fires is still an answer`() {
        // The control. Without it a settle that renamed EVERYTHING would look
        // identical to the test above.
        val attempt = firedWatchdog()

        assertEquals("ok", attempt.settle(Result.success("ok")).getOrNull())
    }

    @Test
    fun `a failure the watchdog did not cause is passed through untouched`() {
        // The other control: only the watchdog's own doing is renamed, so a
        // real DecodingError still reaches the host as one.
        val attempt = BoundedAttempt(60_000, Handler(Looper.getMainLooper())) { it.run() }
        val boom = WarpLinkError.DecodingError(JSONException("Unterminated object"))

        assertSame(boom, attempt.settle(Result.failure<String>(boom)).exceptionOrNull())
    }

    /** An attempt whose watchdog has already fired, with nothing on the wire. */
    private fun firedWatchdog(): BoundedAttempt {
        val attempt = BoundedAttempt(0, Handler(Looper.getMainLooper())) { it.run() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return attempt
    }

    private fun stub() =
        LoopbackJsonServer(RESOLVE_JSON).also { stubs.add(it) }

    private companion object {
        const val RESOLVE_JSON =
            """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":null,"android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
