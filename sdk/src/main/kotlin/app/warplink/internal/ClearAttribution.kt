package app.warplink.internal

import app.warplink.WarpLinkError

/**
 * What a `clearAttribution` call decided, built while the SDK lock is held and
 * acted on after it is released, so no host callback runs under a lock.
 *
 * @property notifyWaiting answers the callers that were parked on the check the
 * clear abandoned.
 * @property restart the session whose automatic check starts over, if any.
 */
internal class ClearAttributionOutcome(
    val result: Result<Unit>,
    val notifyWaiting: () -> Unit = {},
    val restart: SdkSession? = null,
)

/**
 * Clears the Test attribution state of [session]. The caller holds the SDK lock,
 * so a concurrent `configure` cannot point the clear at another key's stores.
 *
 * A Production key is a refused no-op that succeeds: a live install's
 * attribution is never cleared by SDK code. The in-flight check is abandoned and
 * the storage cleared in one `stateLock` hold, so the abandoned check's late
 * answer finds itself stale and writes nothing.
 */
internal fun clearTestAttribution(
    session: SdkSession?,
    checks: DeferredCheckCoordinator,
): ClearAttributionOutcome {
    if (session == null) return ClearAttributionOutcome(Result.failure(WarpLinkError.NotConfigured))
    val storage = session.storage
    if (storage.environment != KeyEnvironment.TEST) {
        session.logger.log("clearAttribution ignored: it only applies to a wl_test_ key")
        return ClearAttributionOutcome(Result.success(Unit))
    }
    val (cleared, notifyWaiting) = synchronized(storage.stateLock) {
        val notify = checks.abandonStaged()
        storage.clearAttribution() to notify
    }
    if (!cleared) {
        return ClearAttributionOutcome(
            Result.failure(
                WarpLinkError.DecodingError(IllegalStateException("Could not clear stored attribution data."))
            ),
            notifyWaiting,
        )
    }
    session.logger.log("Test attribution cleared")
    return ClearAttributionOutcome(Result.success(Unit), notifyWaiting, restart = session)
}
