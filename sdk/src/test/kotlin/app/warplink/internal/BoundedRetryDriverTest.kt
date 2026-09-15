package app.warplink.internal

import app.warplink.WarpLinkError
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The retry driver itself, with no network and no looper.
 *
 * A file of its own rather than a second class in BoundedRetryTest.kt: that
 * file already holds the classifier and the two together would run past the
 * 200 line cap.
 */
class BoundedRetryDriverTest {

    /** Runs everything inline, so the test asserts on the next line. */
    private class InlineScheduler : RetryScheduler {
        val delays = mutableListOf<Long>()
        override fun schedule(delayMs: Long, block: () -> Unit) {
            delays.add(delayMs)
            block()
        }
    }

    private val transient = WarpLinkError.NetworkError(SocketTimeoutException("read timed out"))

    @Test
    fun `a success on the first attempt runs once`() {
        val scheduler = InlineScheduler()
        var attempts = 0
        var delivered: Result<Int>? = null

        BoundedRetry(scheduler, RetrySettings.NO_WAIT, elapsedMs = { 0L }).run(
            attempt = { _, done -> attempts++; done(Result.success(7)) },
            deliver = { delivered = it },
        )

        assertEquals(1, attempts)
        assertEquals(0, scheduler.delays.size)
        assertEquals(7, delivered!!.getOrNull())
    }

    @Test
    fun `a transient failure then a success delivers once`() {
        val scheduler = InlineScheduler()
        var attempts = 0
        var deliveries = 0

        BoundedRetry(scheduler, RetrySettings.NO_WAIT, elapsedMs = { 0L }).run(
            attempt = { number, done ->
                attempts++
                done(if (number == 1) Result.failure(transient) else Result.success(number))
            },
            deliver = { deliveries++ },
        )

        assertEquals(2, attempts)
        assertEquals(1, deliveries)
    }

    @Test
    fun `three transient failures stop at three`() {
        val scheduler = InlineScheduler()
        var attempts = 0
        var delivered: Result<Int>? = null

        BoundedRetry(scheduler, RetrySettings.NO_WAIT, elapsedMs = { 0L }).run(
            attempt = { _, done -> attempts++; done(Result.failure(transient)) },
            deliver = { delivered = it },
        )

        // Exactly three. An unbounded loop is the mutation this pins.
        assertEquals(3, attempts)
        assertIs<WarpLinkError.NetworkError>(delivered!!.exceptionOrNull())
    }

    @Test
    fun `a refusal is not retried`() {
        var attempts = 0
        // Explicit <Unit>: nothing in a failure-only run tells the compiler
        // what a success would have carried.
        BoundedRetry(InlineScheduler(), RetrySettings.NO_WAIT, elapsedMs = { 0L }).run<Unit>(
            attempt = { _, done -> attempts++; done(Result.failure(WarpLinkError.LinkNotFound)) },
            deliver = { },
        )
        assertEquals(1, attempts)
    }

    @Test
    fun `one slow attempt spends the whole budget and there is no second`() {
        // readTimeout bounds the gap between bytes, not the response, so a
        // server that drips bytes makes one attempt unbounded. The elapsed cap
        // is what actually stops the run.
        var clock = 0L
        var attempts = 0

        BoundedRetry(InlineScheduler(), RetrySettings.DEFAULT, elapsedMs = { clock }).run<Unit>(
            attempt = { _, done ->
                attempts++
                clock += RetrySettings.DEFAULT.totalBudgetMs + 1
                done(Result.failure(transient))
            },
            deliver = { },
        )

        assertEquals(1, attempts)
    }

    @Test
    fun `a superseded run stops attempting and still answers its own caller`() {
        var attempts = 0
        var deliveries = 0

        BoundedRetry(
            InlineScheduler(), RetrySettings.NO_WAIT,
            elapsedMs = { 0L }, isCurrent = { false },
        ).run<Unit>(
            attempt = { _, done -> attempts++; done(Result.failure(transient)) },
            deliver = { deliveries++ },
        )

        // Stops the network work, but still answers. Staying silent to the host
        // is AutoLinkHandler's decision, because swallowing the answer here
        // would strand every caller parked on the same claim.
        assertEquals(1, attempts)
        assertEquals(1, deliveries)
    }
}
