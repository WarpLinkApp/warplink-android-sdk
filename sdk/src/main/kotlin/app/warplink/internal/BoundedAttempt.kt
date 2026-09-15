package app.warplink.internal

import android.os.Handler
import app.warplink.WarpLinkError
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One attempt's connection, and the timer that abandons it if it runs long.
 *
 * `connectTimeout` and `readTimeout` are not enough on their own. `readTimeout`
 * is SO_TIMEOUT, which bounds ONE read rather than the response, so a server
 * answering one byte every half second resets it for ever and the attempt never
 * ends. The elapsed cap does not save it either: that is only consulted BETWEEN
 * attempts, so it never abandons an attempt that is still running. Without a
 * bound on the attempt itself the twelve second worst case held for a silent
 * connection and not for a slow one, and both are the same weak uplink.
 *
 * @param timeoutMs this attempt's own bound, or null for a caller that is not
 * retrying and therefore has no attempt to abandon.
 * @param timer the SDK's only timer, the main handler. The runnable it fires
 * hops onto [executor] before touching the socket: closing one is not work the
 * main thread may do.
 */
internal class BoundedAttempt(
    private val timeoutMs: Int?,
    private val timer: Handler,
    private val executor: Executor,
) {

    private val connection = AtomicReference<HttpURLConnection?>(null)
    private val abandoned = AtomicBoolean(false)

    private val watchdog: Runnable? = timeoutMs?.let { ms ->
        Runnable {
            abandoned.set(true)
            executor.execute { connection.get()?.disconnect() }
        }.also { timer.postDelayed(it, ms.toLong()) }
    }

    /**
     * Record the connection this attempt is running on.
     *
     * Disconnects at once when the watchdog has already fired: without that, a
     * timer landing between issuing the attempt and recording its connection
     * would find nothing to stop, and the bound would silently not apply.
     */
    fun open(conn: HttpURLConnection) {
        connection.set(conn)
        if (abandoned.get()) conn.disconnect()
    }

    /** Stop this attempt, for a caller that supersedes it. */
    fun cancel() {
        connection.getAndSet(null)?.disconnect()
    }

    /**
     * Call off the watchdog and name its own disconnection for what it was.
     *
     * The watchdog abandons an attempt by disconnecting mid-response, and what
     * that surfaces as depends on how far the response had got: an IOException
     * before the status line, but a **JSONException** on a body cut in half.
     * `isRetryable` maps that second one to `DecodingError` and refuses to retry
     * it, so left alone the bound would END the run instead of buying the retry
     * it exists to buy. A failure this watchdog caused is therefore reported as
     * the timeout it is.
     *
     * A success is left exactly as it arrived: an answer that landed as the
     * timer fired is still an answer.
     */
    fun <T> settle(result: Result<T>): Result<T> {
        watchdog?.let { timer.removeCallbacks(it) }
        if (result.isSuccess || !abandoned.get()) return result
        return Result.failure(
            WarpLinkError.NetworkError(
                SocketTimeoutException("attempt abandoned after ${timeoutMs}ms")
            )
        )
    }
}
