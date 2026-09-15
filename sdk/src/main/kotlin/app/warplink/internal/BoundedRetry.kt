package app.warplink.internal

import android.os.SystemClock
import kotlin.random.Random

/**
 * Runs a block on a background thread after a delay.
 *
 * An interface rather than a Handler here so the driver never touches Android
 * threading directly and a test can run everything inline. ApiClient is the one
 * production implementation, because it owns both the executor and the handler.
 */
internal fun interface RetryScheduler {
    fun schedule(delayMs: Long, block: () -> Unit)
}

/**
 * A request that is on the wire and can be stopped.
 *
 * A superseded tap must not merely be ignored: its connection would stay open
 * for the rest of its readTimeout, on the network least able to spare it.
 */
internal fun interface Cancellable {
    fun cancel()
}

/**
 * Runs one request up to [settings].maxAttempts times, waiting between
 * attempts, and stopping early when the work is superseded or the budget is
 * spent.
 *
 * Deliberately above ApiClient rather than inside it: ApiClientTest drives a
 * real socket at a closed port and asserts one NetworkError, so a retry inside
 * ApiClient.resolveLink would turn every deliberate failure in that suite into
 * three attempts with real backoff.
 */
internal class BoundedRetry(
    private val scheduler: RetryScheduler,
    private val settings: RetrySettings = RetrySettings.DEFAULT,
    /** Monotonic and counts through sleep, like the dedupe window's clock. */
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * False once this work has been superseded. A superseded run stops
     * attempting but STILL answers its own caller: swallowing the answer here
     * would strand a manual `handleDeepLink` caller and everyone parked on the
     * same AutoLinkHandler claim. Staying silent to the host is decided where
     * the host's sink is.
     */
    private val isCurrent: () -> Boolean = { true },
) {

    fun <T> run(
        attempt: (Int, (Result<T>) -> Unit) -> Unit,
        deliver: (Result<T>) -> Unit,
    ) {
        perform(1, elapsedMs(), attempt, deliver)
    }

    private fun <T> perform(
        number: Int,
        startedAt: Long,
        attempt: (Int, (Result<T>) -> Unit) -> Unit,
        deliver: (Result<T>) -> Unit,
    ) {
        attempt(number) { result ->
            val wait = nextWait(result, number, startedAt)
            if (wait == null) {
                deliver(result)
                return@attempt
            }
            scheduler.schedule(wait) {
                if (!isCurrent()) deliver(result)
                else perform(number + 1, startedAt, attempt, deliver)
            }
        }
    }

    /** The wait before the next attempt, or null when there must not be one. */
    private fun <T> nextWait(result: Result<T>, number: Int, startedAt: Long): Long? {
        val error = result.exceptionOrNull() ?: return null
        if (!isCurrent()) return null
        if (!isRetryable(error)) return null
        // The elapsed cap, not just the attempt count: readTimeout bounds the
        // gap between bytes rather than the response, so three attempts that
        // each crawl outlast any budget stated in attempts alone.
        if (elapsedMs() - startedAt >= settings.totalBudgetMs) return null
        // `maxAttempts` is the bound, and it is the only one. `delaysMs` is the
        // wait SCHEDULE: a run with more attempts than waits repeats the last
        // wait rather than stopping, so a constant named "max attempts" cannot
        // be quietly overruled by the length of a list of delays.
        if (number >= settings.maxAttempts) return null
        if (settings.delaysMs.isEmpty()) return null
        val index = (number - 1).coerceAtMost(settings.delaysMs.lastIndex)
        val jitter = if (settings.maxJitterMs > 0) Random.nextLong(settings.maxJitterMs) else 0L
        return settings.delaysMs[index] + jitter
    }
}
