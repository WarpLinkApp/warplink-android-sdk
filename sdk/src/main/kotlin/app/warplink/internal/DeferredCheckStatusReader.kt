package app.warplink.internal

import app.warplink.DeferredCheckStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The status of [storage]'s install at [nowMs], from what it stored.
 *
 * [checkRunning] is the one fact that lives in memory: a check on the wire, or
 * one waiting for the app to come on screen. A check on the wire has already
 * counted as a failure, so it reads as running before the age and give-up rules
 * or the wait it would otherwise show. A completed check reads as its outcome.
 */
internal fun deferredCheckStatusOf(storage: Storage, checkRunning: Boolean, nowMs: Long): DeferredCheckStatus {
    // The two fields are written together under the same lock, completed first.
    val (completed, link) = synchronized(storage.stateLock) {
        storage.deferredCheckCompleted to storage.cachedAttribution
    }
    if (completed) return if (link != null) DeferredCheckStatus.Matched else DeferredCheckStatus.NoMatch
    val decision = storage.traffic.decide(nowMs)
    return when {
        checkRunning -> DeferredCheckStatus.Checking
        decision == TrafficDecision.SkipInstallAge -> DeferredCheckStatus.SkippedOldInstall
        decision == TrafficDecision.GaveUp -> DeferredCheckStatus.Stopped
        decision is TrafficDecision.WaitForRetry -> DeferredCheckStatus.Waiting(storage.traffic.nextRetryAtMs())
        else -> DeferredCheckStatus.NotChecked
    }
}

/** The state as the debug log prints it, with the retry time for a wait. */
internal fun describeStatus(status: DeferredCheckStatus): String = when (status) {
    DeferredCheckStatus.NotChecked -> "notChecked"
    DeferredCheckStatus.Checking -> "checking"
    DeferredCheckStatus.Matched -> "matched"
    DeferredCheckStatus.NoMatch -> "noMatch"
    DeferredCheckStatus.SkippedOldInstall -> "skippedOldInstall"
    DeferredCheckStatus.Stopped -> "stopped"
    is DeferredCheckStatus.Waiting -> "waiting, retry at ${Instant.ofEpochMilli(status.retryAtMillis).truncatedTo(ChronoUnit.SECONDS)}"
}
