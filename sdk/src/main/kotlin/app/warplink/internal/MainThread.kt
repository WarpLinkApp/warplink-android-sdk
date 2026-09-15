package app.warplink.internal

import android.os.Handler
import android.os.Looper

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Run [block] on the main thread, inline when it is already there.
 *
 * Every SDK callback is documented as arriving on the main thread
 * (docs/api-reference.md), and ApiClient posts each response there itself. A
 * retry run superseded WHILE IT WAITS is the one path that would not: the
 * wait's body hops onto the executor, because the next attempt's
 * HttpURLConnection I/O may not touch the main looper, and the superseded
 * branch answers from there instead of sending anything. Already on the looper
 * this is a plain call, so the ordinary path keeps running inline.
 *
 * Shared by the resolve path and the deferred check rather than kept private to
 * one of them: the hole is identical on both, and one of them fixing it alone
 * is exactly how the two would drift.
 */
internal fun onMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}
