package app.warplink.internal

import app.warplink.WarpLinkError
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which failures are worth another attempt, and which are refusals the server
 * will simply repeat.
 */
class RetryClassifierTest {

    @Test
    fun `a dead or flaky connection is retried`() {
        for (cause in listOf(
            SocketTimeoutException("read timed out"),
            ConnectException("connection refused"),
            UnknownHostException("api.warplink.app"),
            IOException("unexpected end of stream"),
        )) {
            assertTrue(isRetryable(WarpLinkError.NetworkError(cause)), cause.toString())
        }
    }

    @Test
    fun `a malformed URL is not a bad network`() {
        // MalformedURLException extends IOException, so the order of the `when`
        // branches is the behaviour. A second attempt builds the same bad URL.
        assertFalse(isRetryable(WarpLinkError.NetworkError(MalformedURLException("nope"))))
    }

    @Test
    fun `only 5xx is retried among server errors`() {
        assertTrue(isRetryable(WarpLinkError.ServerError(500, "")))
        assertTrue(isRetryable(WarpLinkError.ServerError(503, "")))
        // resolveLink's `else ->` branch produces ServerError for these two as
        // well. An expired link and a rate limit are refusals, not weather.
        assertFalse(isRetryable(WarpLinkError.ServerError(410, "")))
        assertFalse(isRetryable(WarpLinkError.ServerError(429, "")))
    }

    @Test
    fun `every refusal is final`() {
        for (error in listOf(
            WarpLinkError.NotConfigured,
            WarpLinkError.InvalidApiKeyFormat,
            WarpLinkError.InvalidApiKey,
            WarpLinkError.InvalidUrl,
            WarpLinkError.LinkNotFound,
            WarpLinkError.PasswordRequired,
        )) {
            assertFalse(isRetryable(error), error.toString())
        }
        assertFalse(isRetryable(WarpLinkError.DecodingError(IOException("bad json"))))
    }

    @Test
    fun `the default budget is three attempts inside about twelve seconds`() {
        val settings = RetrySettings.DEFAULT
        assertEquals(3, settings.maxAttempts)
        assertEquals(listOf(500L, 1_000L), settings.delaysMs)
        assertEquals(12_000L, settings.totalBudgetMs)
    }

    @Test
    fun `every attempt is bounded, attempt 1 included`() {
        val settings = RetrySettings.DEFAULT
        // Attempt 1 above all. ApiClient's own CONNECT_TIMEOUT_MS is 15_000 and
        // READ_TIMEOUT_MS is 30_000 (ApiClient.kt:380-381), and on the network
        // this retry exists for the first attempt hangs rather than failing, so
        // an unbounded attempt 1 spends the whole budget by itself and no retry
        // ever runs.
        assertEquals(4_000, settings.timeoutMsFor(1))
        assertEquals(3_000, settings.timeoutMsFor(2))
        assertEquals(3_000, settings.timeoutMsFor(3))
        assertTrue(settings.timeoutMsFor(1) < 15_000)
    }

    @Test
    fun `the worst case fits the budget`() {
        val settings = RetrySettings.DEFAULT
        val attempts = (1..settings.maxAttempts).sumOf { settings.timeoutMsFor(it).toLong() }
        val waits = settings.delaysMs.sum() + settings.delaysMs.size * settings.maxJitterMs
        // 4000 + 500 + 3000 + 1000 + 3000, plus at most two jitters.
        assertTrue(attempts + waits <= settings.totalBudgetMs)
    }
}

/** What actually stops the driver attempting again. */
class BoundedRetryBoundTest {

    /** Fails every attempt with a dropped connection, and counts them. */
    private fun attemptsUntilStop(settings: RetrySettings): Int {
        var attempts = 0
        var delivered: Result<Unit>? = null
        BoundedRetry({ _, block -> block() }, settings, elapsedMs = { 0L }).run<Unit>(
            attempt = { _, done ->
                attempts++
                done(Result.failure(WarpLinkError.NetworkError(IOException("dropped"))))
            },
            deliver = { delivered = it },
        )
        assertNotNull(delivered, "the run must answer its caller")
        return attempts
    }

    @Test
    fun `maxAttempts is the bound, not the length of the wait schedule`() {
        // One wait for three attempts. A constant named "max attempts" that
        // bounds nothing is a trap for the next reader: raising it would change
        // no behaviour, and the real bound would sit unnamed in the size of
        // delaysMs. A run longer than the schedule repeats its last wait.
        val settings = RetrySettings(
            delaysMs = listOf(0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
        )
        assertEquals(settings.maxAttempts, attemptsUntilStop(settings))
    }

    @Test
    fun `raising maxAttempts raises the number of attempts`() {
        val settings = RetrySettings(
            maxAttempts = 5,
            delaysMs = listOf(0L, 0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
        )
        assertEquals(5, attemptsUntilStop(settings))
    }
}
