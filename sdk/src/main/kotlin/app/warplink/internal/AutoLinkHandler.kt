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
    private val onLink: (Result<WarpLinkDeepLink>) -> Unit,
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
    private var lastUri: String? = null
    private var lastAt: Long = 0L
    private var lastResult: Result<WarpLinkDeepLink>? = null

    /**
     * Bumped by every claim, and captured by the dispatch that took it.
     *
     * The URI alone cannot identify a claim. A re-tap of the SAME link past the
     * dedupe window takes a FRESH claim under the same URI, so the older tap
     * read `key == lastUri` as its own, dropped the newer tap's claim on its
     * way out, and let the newer tap's duplicate through. That duplicate
     * resolved again under a new tap id, which billed a second click for one
     * tap. A counter tells the two claims apart; a string cannot.
     */
    private var claimToken = 0L
    private val waiting =
        mutableMapOf<String, MutableList<(Result<WarpLinkDeepLink>) -> Unit>>()
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

    /** Resolve [uri] and route the result to [onLink], deduped. */
    fun dispatch(uri: Uri) {
        if (!isWarpLinkUri(uri)) return
        val token = synchronized(lock) { claimLocked(uri) }
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
            val superseded = synchronized(lock) { token != claimToken }
            settle(uri, token, result)
            if (superseded) {
                // A newer tap claimed the handler while this one was retrying.
                // One tap means one navigation, so the older one stays silent.
                // Anyone parked on this URI was already answered by settle.
                logger?.log("Superseded by a newer link; not dispatching: $uri")
                return@resolve
            }
            onLink(result)
        }
    }

    /**
     * Resolve [uri] for a manual `handleDeepLink` caller, deduped against
     * automatic dispatch: when an automatic dispatch already claimed this URI
     * inside the window, [callback] receives that dispatch's result instead of
     * triggering a second resolve. Either way the caller gets one answer.
     */
    fun dispatchManual(uri: Uri, callback: (Result<WarpLinkDeepLink>) -> Unit) {
        var settled: Result<WarpLinkDeepLink>? = null
        val token = synchronized(lock) {
            claimLocked(uri).also { claimed ->
                if (claimed == null) {
                    settled = lastResult
                    if (settled == null) waitersFor(uri).add(callback)
                }
            }
        }
        if (token != null) {
            resolve(uri) { result ->
                settle(uri, token, result)
                callback(result)
            }
            return
        }
        logger?.log("Duplicate deep link suppressed, awaiting its result: $uri")
        settled?.let { callback(it) }
    }

    /** Route a deferred result to [onLink]; a no-match (null) is not dispatched. */
    fun dispatchDeferred(result: Result<WarpLinkDeepLink?>) {
        result.onSuccess { deepLink ->
            if (deepLink != null) onLink(Result.success(deepLink))
        }
        result.onFailure { error -> onLink(Result.failure(error)) }
    }

    fun unregister() {
        synchronized(lock) {
            retired = true
            val app = application ?: return
            callbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
            callbacks = null
        }
    }

    /**
     * Take ownership of dispatching [uri], and hand back the token for the
     * claim just taken. Null when the same URI was already claimed inside the
     * dedupe window (auto plus a manual `handleDeepLink` for one link), meaning
     * it must not be resolved a second time. Call under [lock].
     */
    private fun claimLocked(uri: Uri): Long? {
        val key = uri.toString()
        val at = now()
        // The range, rather than a bare `<`, states the invariant the clock
        // gives us. Anything outside it is a fresh tap, so a source that ever
        // did run backwards would dispatch rather than silently swallow.
        if (key == lastUri && (at - lastAt) in 0 until DEDUP_WINDOW_MS) {
            return null
        }
        lastUri = key
        lastAt = at
        lastResult = null
        claimToken += 1
        return claimToken
    }

    /** Publish a dispatch's result to any caller parked on the same URI. */
    private fun settle(uri: Uri, token: Long, result: Result<WarpLinkDeepLink>) {
        val key = uri.toString()
        val parked = synchronized(lock) {
            // A newer claim may have been taken meanwhile, for this URI or for
            // another; only the current claim's own result may touch the claim.
            if (token == claimToken) {
                if (result.isSuccess) {
                    lastResult = result
                } else {
                    // A failed resolve (offline, server error) is not a
                    // duplicate to suppress: drop the claim so a host retrying
                    // the same link really re-resolves instead of being handed
                    // the cached failure back for the rest of the window.
                    lastUri = null
                    lastResult = null
                }
            }
            // Drained whatever the token says. A caller parked on this URI is
            // owed an answer by whichever dispatch settles first, and a claim
            // taken for a DIFFERENT URI would otherwise leave it parked for the
            // life of the process.
            waiting.remove(key).orEmpty()
        }
        parked.forEach { it(result) }
    }

    /** Call under [lock]. */
    private fun waitersFor(uri: Uri) =
        waiting.getOrPut(uri.toString()) { mutableListOf() }

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

    companion object {
        private const val DEDUP_WINDOW_MS = 1500L
    }
}
