package app.warplink

import android.os.Looper
import android.os.SystemClock
import org.robolectric.Shadows
import java.time.Duration
import java.util.concurrent.CountDownLatch

// Waiting and advancing time, deliberately kept apart.
//
// Every SDK callback is posted to the main handler and Robolectric only runs what a
// test idles into it, so a test cannot simply block on a latch. The obvious fix,
// idling the looper *for* a duration, is a trap: `idleFor` moves Robolectric's clock,
// and the per-attempt watchdog in `BoundedAttempt` is armed with `postDelayed`
// against that same clock while the socket it guards runs in real time. A loop that
// idles 500 ms of virtual time per 50 ms of real time reaches a four second deadline
// in 400 ms, so the watchdog cancels a request that was about to succeed and the test
// sees a `NetworkError` it never asked for.
//
// So the waits below never move the clock. They run what is due, sleep a few real
// milliseconds, and repeat. Only `advanceBy` moves it, it says so in its name, and a
// test calls it when shadow time is the subject rather than an accident.

/** How long to sleep between drains. Real milliseconds, small enough to stay responsive. */
private const val POLL_MS = 2L

private fun mainLooper() = Shadows.shadowOf(Looper.getMainLooper())

/**
 * Run due main-looper work until [condition] holds, without moving the clock.
 *
 * Throws on timeout rather than returning, because a wait that gave up has not
 * established the precondition the assertions below it are about to rely on, and a
 * bare assertion failure would send the reader to the wrong place.
 */
internal fun idleMainLooperUntil(
    timeoutMs: Long = 10_000,
    message: String = "the awaited work never happened",
    condition: () -> Boolean,
) {
    val looper = mainLooper()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        looper.idle()
        if (condition()) return
        Thread.sleep(POLL_MS)
    }
    looper.idle()
    if (!condition()) {
        throw AssertionError("timed out after ${timeoutMs}ms: $message")
    }
}

/** The latch form, which is what most call sites want. */
internal fun idleMainLooperUntil(
    latch: CountDownLatch,
    timeoutMs: Long = 10_000,
    message: String = "a latch never fired, so the SDK never answered",
) = idleMainLooperUntil(timeoutMs, message) { latch.count == 0L }

/**
 * Move Robolectric's clock forward by [millis] and run whatever that makes due.
 *
 * The ONLY thing here that moves the clock. Call it when shadow time is the subject:
 * a dedupe window that must expire, a retry wait the test is parked in, or a watchdog
 * whose firing is the behaviour under test. Do not call it to make a socket hurry up;
 * it cannot, and it will fire the watchdog guarding that socket instead.
 */
internal fun advanceBy(millis: Long) {
    mainLooper().idleFor(Duration.ofMillis(millis))
}

/**
 * Whether a main-looper timer is armed for between [notBeforeMs] and [notAfterMs] from
 * now. Reads the clock, never moves it.
 *
 * The SDK arms exactly two kinds of timer: an attempt's own watchdog, armed before its
 * request goes out, and the wait before a retry, armed once that attempt has failed.
 * Both are a `postDelayed` on the main handler, so a test that is about to move the
 * clock has to know which of the two it is moving onto, and it must not move at all
 * until the one it means is actually armed. The settings the test passed in say how far
 * out each of them is, so the window is how a test names the timer it means, and this
 * is what makes "that attempt has failed" observable without guessing at how long a
 * socket takes.
 *
 * Only a timer strictly in the future counts. A callback the SDK posts with no delay is
 * due the instant it lands and [idleMainLooperUntil] runs it, so an undelayed post is
 * never the thing a test is waiting to advance onto.
 */
internal fun isTimerArmed(
    notBeforeMs: Long = 0,
    notAfterMs: Long = Long.MAX_VALUE,
): Boolean {
    val next = mainLooper().nextScheduledTaskTime
    if (next.isZero) return false
    val dueIn = next.toMillis() - SystemClock.uptimeMillis()
    return dueIn > 0 && dueIn >= notBeforeMs && dueIn <= notAfterMs
}

/** The waiting form of [isTimerArmed], for a test that has to reach that state first. */
internal fun idleUntilTimerArmed(
    notBeforeMs: Long = 0,
    notAfterMs: Long = Long.MAX_VALUE,
    message: String = "no timer was ever armed in that window",
) = idleMainLooperUntil(message = message) { isTimerArmed(notBeforeMs, notAfterMs) }

/**
 * Watch for [windowMs] of REAL time, for claims of the form "and nothing else arrived".
 *
 * An absence cannot be waited for, only watched, so this window is a real limit on what
 * the test can prove and is named to say so. Real time, because fast-forwarding while
 * watching for a straggler is how a watchdog gets invited to create one.
 */
internal fun watchMainLooperFor(windowMs: Long = 300) {
    val looper = mainLooper()
    val deadline = System.currentTimeMillis() + windowMs
    while (System.currentTimeMillis() < deadline) {
        looper.idle()
        Thread.sleep(POLL_MS)
    }
    looper.idle()
}
