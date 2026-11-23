package app.warplink.internal

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import app.warplink.WarpLinkDeepLink

/**
 * Drives the SDK's automatic (opt-out) deep link handling and the single
 * [onLink] sink.
 *
 * - Cold start: registers `ActivityLifecycleCallbacks` and resolves the
 *   launching activity's `intent.data`.
 * - Warm start: dispatched from the host one-liner `WarpLink.onNewIntent` (the
 *   lifecycle callbacks have no new-intent hook, so this is unavoidable).
 * - Deferred: results routed here via [dispatchDeferred].
 *
 * A light time-boxed dedupe prevents double-dispatch when auto handling and a
 * manual `handleDeepLink` see the same URI, or when an activity is recreated.
 * The manual caller is answered from the claiming dispatch's result rather than
 * being dropped, so one link means one resolve and one answer per caller. Only a
 * successful resolve is replayable: a failure is never cached, so retrying the
 * same link really retries it.
 */
internal class AutoLinkHandler(
    private val application: Application?,
    private val logger: Logger?,
    private val isWarpLinkUri: (Uri) -> Boolean,
    onLink: (Result<WarpLinkDeepLink>) -> Unit,
    private val resolve: (Uri, (Result<WarpLinkDeepLink>) -> Unit) -> Unit,
    /**
     * Monotonic millisecond source for the dedupe window. Deliberately NOT
     * `System.currentTimeMillis()`: wall clock moves backwards when NITZ or NTP
     * corrects a drifted phone, or when the user edits the date. A backward
     * step makes the elapsed subtraction negative, and a negative value reads
     * as "inside the window", so every repeat tap on a link is dropped until
     * the clock catches up. `elapsedRealtime` cannot run backwards, and it
     * counts through sleep, which matters because a tap can arrive on a device
     * that was dozing between the two.
     *
     * Injectable so a test can drive the backward step this exists to survive.
     */
    private val now: () -> Long = SystemClock::elapsedRealtime
) {

    private val lock = Any()
    private val claims = LinkClaims(now)

    /** The host's sink. A same-options `configure` swaps it for the new call's lambda. */
    @Volatile
    private var sink = onLink
    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    /**
     * Set by [unregister], and never cleared.
     *
     * `WarpLink.configure` retires the previous handler before installing a
     * replacement, and it only ever unregisters the SDK's *current* handler. So
     * a handler that has been retired will never be unregistered again, and
     * anything it registers after that point stays registered for the life of
     * the process. Refusing the registration is what keeps that unreachable
     * state from existing.
     */
    private var retired = false

    /**
     * Register cold-start handling, at most once per handler.
     *
     * No-op if the context is not an `Application` (with a warning), if this
     * handler has been [retired], or if it is already registered. The last two
     * keep one handler to one registration, which is what makes [unregister]
     * total.
     */
    fun registerColdStart() {
        val app = application
        if (app == null) {
            logger?.log(
                "automaticDeepLinks enabled but the provided context is not an " +
                    "Application; deliver cold-start links via WarpLink.onNewIntent " +
                    "or WarpLink.handleDeepLink manually"
            )
            return
        }
        val created = activityCallbacks()
        // The guards and the registration are one step under [lock]: split them
        // and an unregister can land in between, leaving `created` registered
        // with nothing left holding a reference to remove it. [unregister]
        // already calls its counterpart under this same lock.
        synchronized(lock) {
            if (retired) {
                logger?.log("Superseded by a newer configure; not registering cold start")
                return
            }
            if (callbacks != null) return
            callbacks = created
            app.registerActivityLifecycleCallbacks(created)
        }
    }

    /** Routes every later result to [onLink]. A result already being resolved goes to it too. */
    fun replaceSink(onLink: (Result<WarpLinkDeepLink>) -> Unit) {
        sink = onLink
    }

    /** Resolve [uri] and route the result to the sink, deduped. */
    fun dispatch(uri: Uri) {
        if (!isWarpLinkUri(uri)) return
        val token = claims.claim(uri)
        if (token == null) {
            logger?.log("Duplicate deep link suppressed: $uri")
            return
        }
        // settle first: it answers anyone parked on this URI, so a throwing
        // consumer can no longer orphan them.
        resolve(uri) { result ->
            // The token, not the URI. A re-tap of the same link is a newer
            // claim wearing the same string, so a URI comparison called it
            // current and handed the host the overtaken tap's answer.
            val superseded = claims.isSuperseded(token)
            claims.settle(uri, token, result)
            if (superseded) {
                // A newer tap claimed the handler while this one was retrying.
                // One tap means one navigation, so the older one stays silent.
                // Anyone parked on this URI was already answered by settle.
                logger?.log("Superseded by a newer link; not dispatching: $uri")
                return@resolve
            }
            sink(result)
        }
    }

    /**
     * Resolve [uri] for a manual `handleDeepLink` caller, deduped against
     * automatic dispatch: when an automatic dispatch already claimed this URI
     * inside the window, [callback] receives that dispatch's result instead of
     * triggering a second resolve. Either way the caller gets one answer.
     */
    fun dispatchManual(uri: Uri, callback: (Result<WarpLinkDeepLink>) -> Unit) {
        val (token, settled) = claims.claimOrPark(uri, callback)
        if (token != null) {
            resolve(uri) { result ->
                claims.settle(uri, token, result)
                callback(result)
            }
            return
        }
        logger?.log("Duplicate deep link suppressed, awaiting its result: $uri")
        settled?.let { callback(it) }
    }

    /** Route a deferred result to the sink; a no-match (null) is not dispatched. */
    fun dispatchDeferred(result: Result<WarpLinkDeepLink?>) {
        result.onSuccess { deepLink ->
            if (deepLink != null) sink(Result.success(deepLink))
        }
        result.onFailure { error -> sink(Result.failure(error)) }
    }

    fun unregister() {
        synchronized(lock) {
            retired = true
            val app = application ?: return
            callbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
            callbacks = null
        }
    }

    private fun activityCallbacks() =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Only a fresh launch (null bundle) carries a new cold-start link.
                // On a config-change recreation (rotation, dark-mode) getIntent()
                // still returns the original launch intent, which would otherwise
                // re-dispatch the same link once the dedupe window has elapsed.
                if (savedInstanceState == null) {
                    activity.intent?.data?.let { dispatch(it) }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
}
