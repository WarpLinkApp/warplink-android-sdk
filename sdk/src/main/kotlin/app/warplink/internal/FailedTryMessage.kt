package app.warplink.internal

import app.warplink.WarpLinkError
import java.net.SocketTimeoutException

/**
 * What one failed request of a deferred check says in the debug log, so a
 * developer can tell a slow network from a refusing server and sees whether the
 * same launch goes on. [tryNumber] counts the requests of one run, from 1.
 * [willRetry] is the retry driver's decision, not a guess from the error.
 */
internal fun describeFailedTry(
    error: Throwable,
    tryNumber: Int,
    willRetry: Boolean,
    settings: RetrySettings,
): String {
    val what = when {
        error is WarpLinkError.NetworkError && error.cause is SocketTimeoutException ->
            "timed out after ${duration(settings.timeoutMsFor(tryNumber))}"
        error is WarpLinkError.NetworkError -> "could not reach the server"
        error is WarpLinkError.ServerError -> "got status ${error.statusCode}"
        else -> "failed: ${error.message}"
    }
    val next = if (willRetry) ", trying again" else ""
    return "Deferred check request $what (try $tryNumber of ${settings.maxAttempts})$next"
}

private fun duration(ms: Int): String = if (ms % 1_000 == 0) "${ms / 1_000} s" else "$ms ms"
