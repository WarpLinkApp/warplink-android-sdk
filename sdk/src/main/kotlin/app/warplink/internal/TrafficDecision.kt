package app.warplink.internal

/**
 * The checks that failed so far on this install. A request counts as failed from
 * the moment it is sent until a 200 answer is applied, so a check that is
 * abandoned or killed mid-request counts too.
 */
internal data class FailureRecord(
    val count: Int = 0,
    val firstAtMs: Long = 0L,
    val lastAtMs: Long = 0L,
)

/** What the traffic rules allow the deferred check to do right now. */
internal sealed interface TrafficDecision {

    /** Send the request. Raw device signals ride along only when [sendRawSignals] is true. */
    data class Proceed(val sendRawSignals: Boolean) : TrafficDecision

    /** The install is too old to attribute. No request, and the check ends with no match. */
    data object SkipInstallAge : TrafficDecision

    /** A recent check failed. No request until [secondsLeft] has passed. */
    data class WaitForRetry(val secondsLeft: Long) : TrafficDecision

    /** The first failure is older than the give-up time. No request, ever. */
    data object GaveUp : TrafficDecision
}

/** Applies [policy] to this install's age and failure record. A key exempt from the rules never gets here. */
internal fun decideTraffic(
    policy: SdkPolicy,
    installAgeSeconds: Long,
    failures: FailureRecord,
    nowMs: Long,
): TrafficDecision {
    if (installAgeSeconds > policy.maxInstallAgeSeconds) return TrafficDecision.SkipInstallAge
    if (failures.count > 0) {
        if (nowMs - failures.firstAtMs >= policy.giveUpAfterSeconds * 1_000) return TrafficDecision.GaveUp
        val retryAtMs = retryAtMs(policy, failures)
        // A clock set back never holds the check: the wait cannot be measured.
        if (nowMs in failures.lastAtMs until retryAtMs) {
            return TrafficDecision.WaitForRetry((retryAtMs - nowMs + 999) / 1_000)
        }
    }
    return TrafficDecision.Proceed(sendRawSignals = installAgeSeconds <= policy.rawSignalMaxInstallAgeSeconds)
}

/** The first moment the check may send again after [failures], in epoch milliseconds. */
internal fun retryAtMs(policy: SdkPolicy, failures: FailureRecord): Long =
    failures.lastAtMs + policy.retryDelayAfterFailure(failures.count) * 1_000
