package app.warplink

/**
 * Where this install's first-launch link check stands, so a developer can tell a
 * check that was skipped from a check that found no match. Read it from
 * [WarpLink.deferredCheckStatus]. It is derived from state the SDK stored on the
 * device, so it survives a relaunch.
 *
 * A `wl_test_` key is never held back, so it never reports [Waiting],
 * [SkippedOldInstall] or [Stopped].
 */
sealed class DeferredCheckStatus {

    /** No check has settled and none is running. A failed check whose wait is over reads this too. */
    data object NotChecked : DeferredCheckStatus()

    /** A check is on the wire, or is waiting for the app to come on screen. */
    data object Checking : DeferredCheckStatus()

    /** The check finished and found a link. */
    data object Matched : DeferredCheckStatus()

    /** The check finished and found no link. */
    data object NoMatch : DeferredCheckStatus()

    /** A check failed. The SDK sends no request until [retryAtMillis], in epoch milliseconds. */
    data class Waiting(val retryAtMillis: Long) : DeferredCheckStatus()

    /** The install is older than 30 days by default, so no request is sent. */
    data object SkippedOldInstall : DeferredCheckStatus()

    /** The check gave up, 3 days by default after its first failure. No request is sent. */
    data object Stopped : DeferredCheckStatus()
}
