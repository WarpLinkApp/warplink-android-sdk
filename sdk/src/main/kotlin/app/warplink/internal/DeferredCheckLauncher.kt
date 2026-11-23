package app.warplink.internal

import android.app.Application
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError

/**
 * Starts the deferred check, automatic or manual, once an activity is on screen.
 *
 * A call made earlier waits and then runs against the session that is current by
 * then, and never fails for waiting. A completed check answers from the cache at
 * once, since nothing is sent. This is the one entry the automatic check and
 * `checkDeferredDeepLink` share, and so the one the React Native and Flutter
 * bridges reach.
 */
internal class DeferredCheckLauncher(
    private val coordinator: DeferredCheckCoordinator,
    private val currentSession: () -> SdkSession?,
    private val settings: () -> RetrySettings,
) {

    private val foreground = ForegroundGate()

    /** Whether a call is waiting for an activity to come on screen. */
    val hasWaitingCheck: Boolean get() = foreground.hasWaiting

    /** Starts watching [application] for activities, once per application. */
    fun attach(application: Application) = foreground.attach(application)

    /** Stops watching and drops the calls still waiting. */
    fun reset() = foreground.detach()

    fun check(callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        val current = currentSession()
        when {
            current == null -> callback(Result.failure(WarpLinkError.NotConfigured))
            current.storage.deferredCheckCompleted -> run(callback)
            else -> foreground.whenVisible { run(callback) }
        }
    }

    /** Runs the check on [expected] only while it is the current session; otherwise answers success(null). */
    fun checkIfCurrent(expected: SdkSession, callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        foreground.whenVisible {
            if (currentSession() === expected) {
                coordinator.check(expected.deferredCheckInputs(settings()), callback)
            } else {
                callback(Result.success(null))
            }
        }
    }

    private fun run(callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        val current = currentSession()
        if (current == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
        } else {
            coordinator.check(current.deferredCheckInputs(settings()), callback)
        }
    }
}
