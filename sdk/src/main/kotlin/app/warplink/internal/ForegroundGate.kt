package app.warplink.internal

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle

/**
 * Whether the host app has an activity on screen, and a queue for work that
 * waits for one.
 *
 * It counts started and stopped activities through its own lifecycle callbacks,
 * so it needs no dependency and works when the host turned the automatic link
 * handler off, as the React Native and Flutter SDKs do. A host that configures
 * the SDK after its first activity started never sent the callbacks, so
 * [attach] asks the system once whether the process is on screen. That answer
 * is a seed for an activity whose start the gate never saw. The seed ends when
 * the gate sees that activity stop, or when every activity it saw has stopped,
 * unless that activity resumed in between: a resume counts it.
 *
 * Work queued by [whenVisible] runs on the main thread, on the first activity
 * start, in the order it was queued. Nothing waiting is ever dropped or failed.
 */
internal class ForegroundGate(
    private val seedVisible: () -> Boolean = ::processIsOnScreen,
) {

    private val lock = Any()
    private var attachedTo: Application? = null
    private val started = mutableSetOf<Int>()
    private var seeded = false
    private var visible = false
    private val waiting = mutableListOf<() -> Unit>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = activityOnScreen(activity)
        override fun onActivityResumed(activity: Activity) = activityOnScreen(activity)
        override fun onActivityStopped(activity: Activity) = activityStopped(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Whether work is queued for the first activity start. */
    val hasWaiting: Boolean get() = synchronized(lock) { waiting.isNotEmpty() }

    /** Starts counting for [application]. A second call for the same application does nothing. */
    fun attach(application: Application) {
        val ready = synchronized(lock) {
            if (attachedTo === application) return
            attachedTo?.unregisterActivityLifecycleCallbacks(callbacks)
            attachedTo = application
            visible = seedVisible()
            seeded = visible
            started.clear()
            takeWaitingIfVisible()
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        ready.forEach { it() }
    }

    /** Stops counting and drops the work still waiting. */
    fun detach() {
        synchronized(lock) {
            attachedTo?.unregisterActivityLifecycleCallbacks(callbacks)
            attachedTo = null
            started.clear()
            seeded = false
            visible = false
            waiting.clear()
        }
    }

    /**
     * Runs [block] now when an activity is on screen, otherwise on the first
     * start. With no application to count activities for, it runs at once: the
     * gate has nothing to wait for.
     */
    fun whenVisible(block: () -> Unit) {
        val runNow = synchronized(lock) {
            val run = attachedTo == null || visible
            if (!run) waiting.add(block)
            run
        }
        if (runNow) block()
    }

    /**
     * A start or a resume puts [activity] on screen. A resume matters for the
     * seeded activity: it never reports a start, but it resumes when a dialog
     * activity opened over it closes, and the dialog's stop must not leave the
     * count empty while it is on screen. Counting a resume for an activity that
     * already started changes nothing, so the launch activity is not counted twice.
     */
    private fun activityOnScreen(activity: Activity) {
        val ready = synchronized(lock) {
            started.add(System.identityHashCode(activity))
            visible = true
            takeWaitingIfVisible()
        }
        ready.forEach { it() }
    }

    private fun activityStopped(activity: Activity) = synchronized(lock) {
        // A stop for an activity whose start was never seen ends the seed: it
        // was the seeded one. When the last seen activity stops, the seed was
        // that same launch activity, which a cold start reports after the seed.
        if (!started.remove(System.identityHashCode(activity))) seeded = false
        if (started.isEmpty()) seeded = false
        visible = started.isNotEmpty() || seeded
    }

    private fun takeWaitingIfVisible(): List<() -> Unit> =
        if (visible) waiting.toList().also { waiting.clear() } else emptyList()
}

private fun processIsOnScreen(): Boolean {
    val state = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(state)
    return isOnScreen(state)
}

/**
 * Whether [state] says an activity of this process is on screen.
 *
 * Only a plain foreground importance counts. A process another app keeps at
 * foreground or visible importance through a service or a content provider it
 * uses (a reason code other than unknown) may have no activity on screen, and a
 * wrong yes is never corrected. A wrong no only waits for the first activity start.
 */
internal fun isOnScreen(state: ActivityManager.RunningAppProcessInfo): Boolean =
    state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
        state.importanceReasonCode == ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN
