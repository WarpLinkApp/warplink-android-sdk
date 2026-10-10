package app.warplink.internal

import app.warplink.WarpLinkError
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 429 gets one retry after the server's `Retry-After`, capped at five
 * seconds, and otherwise reaches the caller as a [WarpLinkError.ServerError].
 */
class RateLimitRetryTest {

    private class InlineScheduler : RetryScheduler {
        val delays = mutableListOf<Long>()
        override fun schedule(delayMs: Long, block: () -> Unit) {
            delays.add(delayMs)
            block()
        }
    }

    private fun limited(retryAfter: Double?) = WarpLinkError.ServerError(429, "x", retryAfter)

    private fun connection(status: Int, retryAfter: String?): HttpURLConnection =
        object : HttpURLConnection(URL("https://api.warplink.app/v1/x")) {
            override fun getResponseCode() = status
            override fun getHeaderField(name: String?) =
                if (name == "Retry-After") retryAfter else null
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun connect() = Unit
        }

    /** Runs an attempt that always fails with [error]; returns attempts and waits. */
    private fun run(error: WarpLinkError): Pair<Int, List<Long>> {
        val scheduler = InlineScheduler()
        var attempts = 0
        BoundedRetry(scheduler, RetrySettings.NO_WAIT, elapsedMs = { 0L }).run<Int>(
            attempt = { _, done -> attempts++; done(Result.failure(error)) },
            deliver = {},
        )
        return attempts to scheduler.delays
    }

    @Test
    fun `a retry-after of three seconds retries once after three seconds`() {
        assertEquals(2 to listOf(3_000L), run(limited(3.0)))
    }

    @Test
    fun `a retry-after of exactly five seconds still retries`() {
        assertEquals(2 to listOf(5_000L), run(limited(5.0)))
    }

    @Test
    fun `a retry-after over five seconds is not retried`() {
        assertEquals(1 to emptyList(), run(limited(6.0)))
    }

    @Test
    fun `a missing retry-after retries once after one second`() {
        assertEquals(2 to listOf(1_000L), run(limited(null)))
    }

    @Test
    fun `a second 429 is not retried again`() {
        val (attempts, delays) = run(limited(0.0))
        assertEquals(2, attempts)
        assertEquals(1, delays.size)
    }

    @Test
    fun `the retry outcome reaches the caller once`() {
        var attempt = 0
        val delivered = mutableListOf<Result<Int>>()
        BoundedRetry(InlineScheduler(), RetrySettings.NO_WAIT, elapsedMs = { 0L }).run<Int>(
            attempt = { _, done ->
                attempt++
                done(if (attempt == 1) Result.failure(limited(2.0)) else Result.success(9))
            },
            deliver = { delivered.add(it) },
        )
        assertEquals(1, delivered.size)
        assertEquals(9, delivered.single().getOrNull())
    }

    @Test
    fun `an exhausted rate limit surfaces as a server error 429`() {
        var delivered: Result<Int>? = null
        BoundedRetry(InlineScheduler(), RetrySettings.NO_WAIT, elapsedMs = { 0L }).run<Int>(
            attempt = { _, done -> done(Result.failure(limited(9.0))) },
            deliver = { delivered = it },
        )
        val error = assertIs<WarpLinkError.ServerError>(delivered!!.exceptionOrNull())
        assertEquals(429, error.statusCode)
        assertEquals(9.0, error.retryAfterSeconds)
    }

    @Test
    fun `a superseded run does not retry a rate limit`() {
        var attempts = 0
        BoundedRetry(InlineScheduler(), RetrySettings.NO_WAIT, elapsedMs = { 0L }, isCurrent = { false })
            .run<Int>(
                attempt = { _, done -> attempts++; done(Result.failure(limited(1.0))) },
                deliver = {},
            )
        assertEquals(1, attempts)
    }

    @Test
    fun `other client errors are still final`() {
        assertEquals(1, run(WarpLinkError.ServerError(410, "x")).first)
        assertFalse(RateLimit.isRateLimited(WarpLinkError.ServerError(503, "x")))
        assertNull(RateLimit.waitMs(WarpLinkError.ServerError(503, "x")))
    }

    @Test
    fun `the 429 error carries the parsed retry-after header`() {
        assertEquals(4.0, unexpectedStatus(connection(429, " 4 ")).retryAfterSeconds)
        assertEquals(0.5, unexpectedStatus(connection(429, "0.5")).retryAfterSeconds)
    }

    @Test
    fun `an unreadable retry-after is treated as absent`() {
        for (header in listOf("soon", "-2", "Wed, 21 Oct 2026 07:28:00 GMT", null)) {
            assertNull(unexpectedStatus(connection(429, header)).retryAfterSeconds, "$header")
        }
    }

    @Test
    fun `a retry-after on another status is ignored`() {
        val error = unexpectedStatus(connection(503, "3"))
        assertEquals(503, error.statusCode)
        assertNull(error.retryAfterSeconds)
        assertTrue(RateLimit.isRateLimited(limited(null)))
    }

    @Test
    fun `a retry-after just over five seconds is not retried`() {
        assertEquals(1 to emptyList(), run(limited(5.0001)))
        assertNull(RateLimit.waitMs(limited(5.0001)))
    }

    @Test
    fun `a 500 then a 429 then a 200 delivers the 200`() {
        val scheduler = InlineScheduler()
        var attempt = 0
        var delivered: Result<Int>? = null
        BoundedRetry(scheduler, RetrySettings.NO_WAIT, elapsedMs = { 0L }).run<Int>(
            attempt = { _, done ->
                attempt++
                done(
                    when (attempt) {
                        1 -> Result.failure(WarpLinkError.ServerError(500, "x"))
                        2 -> Result.failure(limited(2.0))
                        else -> Result.success(200)
                    }
                )
            },
            deliver = { delivered = it },
        )
        assertEquals(3, attempt)
        assertEquals(200, delivered!!.getOrNull())
        assertEquals(2_000L, scheduler.delays.last())
    }

    @Test
    fun `a 429 then a 429 then a 429 stops after one retry`() {
        assertEquals(2, run(limited(0.0)).first)
    }

    @Test
    fun `a 429 retry that would pass the time budget is not sent`() {
        val settings = RetrySettings.NO_WAIT.copy(totalBudgetMs = 4_000L)
        var attempts = 0
        BoundedRetry(InlineScheduler(), settings, elapsedMs = { 0L }).run<Int>(
            attempt = { _, done -> attempts++; done(Result.failure(limited(4.0))) },
            deliver = {},
        )
        assertEquals(1, attempts)
    }

    @Test
    fun `the two-argument ServerError constructor still exists`() {
        val ctor = WarpLinkError.ServerError::class.java
            .getConstructor(Int::class.javaPrimitiveType, String::class.java)
        val error = ctor.newInstance(503, "down")
        assertEquals(503, error.statusCode)
        assertNull(error.retryAfterSeconds)
    }
}
