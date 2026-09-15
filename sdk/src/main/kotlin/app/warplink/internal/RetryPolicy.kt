package app.warplink.internal

import app.warplink.WarpLinkError
import java.io.IOException
import java.net.MalformedURLException

/**
 * How hard, and for how long, a transient failure is retried.
 *
 * A value class rather than constants so a test can remove the waits and drive
 * the budget, and so the two call sites (resolve, deferred check) cannot drift
 * into different budgets.
 */
internal data class RetrySettings(
    /** The first attempt plus two retries, and the bound BoundedRetry enforces. */
    val maxAttempts: Int = 3,
    /** Base waits before attempts 2 and 3. The wait SCHEDULE, not the bound: a
     *  run with more attempts than waits repeats the last one. */
    val delaysMs: List<Long> = listOf(500L, 1_000L),
    /** Up to this much extra per wait, so a fleet coming back from one outage
     *  does not resend in lockstep. */
    val maxJitterMs: Long = 150L,
    /**
     * The whole run stops once this much has elapsed, checked before each
     * retry. The SECOND guard, not the only one: every attempt now sets its own
     * connect and read timeouts, but `readTimeout` bounds the gap BETWEEN
     * BYTES rather than the response, so a server that drips bytes can outlast
     * a per-attempt timeout.
     */
    val totalBudgetMs: Long = 12_000L,
    /**
     * Attempt 1's own timeout. Longer than a retry's, because a first attempt
     * on a slow but working connection is the common case, and short enough
     * that two more attempts still fit inside the budget.
     *
     * Not optional, and not ApiClient's own timeouts. CONNECT_TIMEOUT_MS is
     * 15_000 and READ_TIMEOUT_MS is 30_000 (ApiClient.kt:380-381), and on the
     * network this exists for the first attempt does not fail fast, it hangs.
     * An unbounded attempt 1 spends the whole budget and no retry ever runs,
     * which is the retry failing in the exact case it exists for.
     */
    val firstAttemptTimeoutMs: Int = 4_000,
    /**
     * Attempts 2 and 3. The connection has already failed once by then, so
     * waiting as long again buys little.
     */
    val retryTimeoutMs: Int = 3_000,
) {
    /** The timeout for attempt [attempt], 1-based. Every attempt has one. */
    fun timeoutMsFor(attempt: Int): Int =
        if (attempt == 1) firstAttemptTimeoutMs else retryTimeoutMs

    companion object {
        val DEFAULT = RetrySettings()

        /** No waits and no budget, for a test that asserts on what was sent
         *  rather than racing Robolectric's shadow clock. */
        val NO_WAIT = RetrySettings(
            delaysMs = listOf(0L, 0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
        )

        /**
         * The shipped per-attempt timeouts with no elapsed cap, for the tests
         * that have to let an attempt reach its own bound.
         *
         * Without this the cap could end the run before the retry, and the
         * per-attempt bound would go unobserved. Those tests measure on
         * `SystemClock.elapsedRealtime()`, which is the clock the bound is
         * scheduled against; Robolectric drives it far faster than the wall
         * clock, and on a device the two are the same clock.
         */
        val REAL_TIMEOUTS_NO_CAP = RetrySettings(totalBudgetMs = Long.MAX_VALUE)
    }
}

/** Whether another attempt could plausibly do better. */
internal fun isRetryable(error: Throwable): Boolean = when (error) {
    is WarpLinkError.NetworkError -> isTransientTransport(error.cause)
    // `statusCode >= 500`, not "any ServerError". resolveLink's `else ->` branch
    // also produces ServerError for 410 GONE and for 429.
    is WarpLinkError.ServerError -> error.statusCode >= 500
    else -> false
}

private fun isTransientTransport(cause: Throwable?): Boolean = when (cause) {
    // Checked FIRST: it extends IOException, and a second attempt would build
    // the same bad URL and fail the same way.
    is MalformedURLException -> false
    // Refused, unresolvable, timed out, reset mid-response. All of them are the
    // weather this retry exists for.
    is IOException -> true
    else -> false
}
