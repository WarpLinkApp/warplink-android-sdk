package app.warplink.internal

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.referral.ReferrerTokenOutcome
import app.warplink.internal.referral.redeemReferrerToken

/**
 * What the steps of one deferred check share.
 *
 * [callback] answers the caller. A step that follows a network answer runs on
 * the thread that read it, so the gate is saved before anything reaches the
 * main thread, and it answers through [callback], which hops to the main thread.
 */
private class DeferredRun(
    val storage: Storage,
    val fingerprintCollector: FingerprintCollector,
    val apiClient: ApiClient,
    val logger: Logger?,
    val isCurrentCheck: () -> Boolean,
    val settings: RetrySettings,
    val attempt: AttemptRun,
    val sendRawSignals: Boolean,
    callback: (Result<WarpLinkDeepLink?>) -> Unit,
) {
    val callback: (Result<WarpLinkDeepLink?>) -> Unit = { result -> onMainThread { callback(result) } }

    /** The device signals this run may send, none when the install is too old for them. */
    fun signals(): DeviceSignals? = if (sendRawSignals) fingerprintCollector.collect() else null
}

/**
 * @param isCurrentCheck reports whether this check is still the SDK's live one.
 * A reconfigure or reset abandons an in-flight check, and its response must then
 * apply nothing: see [handleAttributionResponse]. Defaults to always-current for
 * callers with no generation to track (tests).
 * @param sendRawSignals false when the install is too old for raw device signals:
 * a request then carries only the stronger evidence (link id, recovery token),
 * and a check with none sends nothing.
 * @param attempt the run's claim on an attempt number, counted when its first
 * request is sent.
 */
internal fun performDeferredCheck(
    storage: Storage,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    installReferrerReader: ReferrerSource?,
    logger: Logger?,
    isCurrentCheck: () -> Boolean = { true },
    /** Overridden only by tests, so a retry test asserts on what was sent
     *  rather than racing Robolectric's shadow clock. */
    settings: RetrySettings = RetrySettings.DEFAULT,
    sendRawSignals: Boolean = true,
    attempt: AttemptRun = AttemptRun(storage.traffic, sendRawSignals = sendRawSignals),
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    // This check supersedes any older one, whose attempt would otherwise go on
    // holding its connection for the rest of its readTimeout.
    cancelInFlightAttributionMatch(MatchSlot.DEFERRED_CHECK)

    if (storage.deferredCheckCompleted) {
        logger?.log("Deferred check already completed, returning cached attribution")
        callback(Result.success(storage.cachedAttribution))
        return
    }

    if (!markAttempted(storage, isCurrentCheck, logger)) {
        callback(Result.success(null))
        return
    }

    val run = DeferredRun(
        storage, fingerprintCollector, apiClient, logger, isCurrentCheck, settings,
        attempt, sendRawSignals, callback
    )
    if (installReferrerReader != null) {
        logger?.log("First launch: trying Play Install Referrer")
        tryReferrerThenFallback(installReferrerReader, run)
    } else {
        logger?.log("First launch: collecting device signals")
        collectAndMatch(run)
    }
}

/**
 * Records that this install tried a check, unless the check was abandoned
 * first. It never consumes the completion flag: that is set only on a definitive
 * server answer (see [handleAttributionResponse]), so an attempt that fails
 * tries again once its backoff has passed. Returns whether the check goes on.
 */
private fun markAttempted(storage: Storage, isCurrentCheck: () -> Boolean, logger: Logger?): Boolean {
    val retried = storage.deferredCheckAttempted
    val current = synchronized(storage.stateLock) {
        isCurrentCheck().also { if (it) storage.deferredCheckAttempted = true }
    }
    when {
        !current -> logger?.log("Deferred check abandoned before it started")
        retried -> logger?.log("Retrying deferred check after a previous failed attempt")
    }
    return current
}

private fun tryReferrerThenFallback(reader: ReferrerSource, run: DeferredRun) {
    reader.readReferrer { referrerResult ->
        val read = referrerResult.getOrNull()
        if (read != null && read.hasWarpLinkEvidence) {
            run.logger?.log("Referrer found: ${read.linkId ?: "recovery token only"}")
            matchWithReferrer(read, run)
        } else {
            run.logger?.log(
                "No WarpLink referrer, falling back to probabilistic matching"
            )
            collectAndMatch(run, read?.rawReferrer)
        }
    }
}

private fun matchWithReferrer(read: ReferrerRead, run: DeferredRun) {
    val routeMatches = read.isRoutable(System.currentTimeMillis() / 1000)
    // A recovery token is redeemed alone first: see redeemReferrerToken.
    redeemReferrerToken(
        read.recoveryToken, run.apiClient, run.storage, run.settings, run.isCurrentCheck, run.attempt
    ) { outcome ->
        when (outcome) {
            is ReferrerTokenOutcome.Matched -> handleAttributionResponse(
                outcome.response, run.storage, run.logger, run.isCurrentCheck,
                routeMatches = routeMatches, callback = run.callback
            )
            is ReferrerTokenOutcome.Retry -> run.callback(Result.failure(outcome.error))
            ReferrerTokenOutcome.FallThrough -> matchWithLinkId(read, run, routeMatches)
        }
    }
}

private fun matchWithLinkId(read: ReferrerRead, run: DeferredRun, routeMatches: Boolean) {
    if (read.linkId == null) {
        // The token settled nothing and there is no link id: the check has no
        // evidence left, which collectAndMatch answers by age.
        collectAndMatch(run, routeMatches = routeMatches)
        return
    }
    // deviceId is left null on Android: there is no privacy-safe IDFV analog
    // (ANDROID_ID is disclaimed in the SDK privacy docs), and the Play Install
    // Referrer is the deterministic primary path here. The raw referrer is not
    // sent: it holds the recovery token or test code that redeemReferrerToken
    // already settled, and the server takes it only from an install with no
    // link id or token.
    matchWithRetry(
        run.apiClient, MatchSlot.DEFERRED_CHECK, run.settings, run.isCurrentCheck,
        run.storage.traffic, run.attempt,
        run.signals(),
        referrer = read.linkId,
        isReinstall = run.storage.isReinstall
    ) { attrResult ->
        attrResult.onFailure { run.callback(Result.failure(it)) }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, run.storage, run.logger, run.isCurrentCheck,
                routeMatches = routeMatches, callback = run.callback
            )
        }
    }
}

private fun collectAndMatch(
    run: DeferredRun,
    installReferrer: String? = null,
    routeMatches: Boolean = true,
) {
    if (!run.sendRawSignals) {
        // Past the raw signal age the only evidence left is the referrer, and
        // there is none. The server rejects a request with nothing to match on,
        // so none is sent, and no attempt is used.
        run.logger?.log("Install too old for raw device signals and no WarpLink referrer; not sending")
        run.callback(Result.success(null))
        return
    }
    // Collection is total: a degraded signal set still produces a request, and a
    // request that misses is recorded server-side where an abandoned one is not.
    matchWithRetry(
        run.apiClient, MatchSlot.DEFERRED_CHECK, run.settings, run.isCurrentCheck,
        run.storage.traffic, run.attempt,
        run.signals(),
        referrer = null,
        installReferrer = installReferrer,
        isReinstall = run.storage.isReinstall
    ) { attrResult ->
        attrResult.onFailure { error ->
            run.callback(Result.failure(error))
        }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, run.storage, run.logger, run.isCurrentCheck,
                routeMatches = routeMatches, callback = run.callback
            )
        }
    }
}
