package app.warplink.internal

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.referral.ReferrerTokenOutcome
import app.warplink.internal.referral.redeemReferrerToken

/**
 * @param isCurrentCheck reports whether this check is still the SDK's live one.
 * A reconfigure or reset abandons an in-flight check, and its response must then
 * apply nothing: see [handleAttributionResponse]. Defaults to always-current for
 * callers with no generation to track (tests).
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
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    // This check supersedes any older one, whose attempt would otherwise go on
    // holding its connection for the rest of its readTimeout.
    cancelInFlightAttributionMatch(MatchSlot.DEFERRED_CHECK)

    if (storage.deferredCheckCompleted) {
        val cached = storage.cachedAttribution
        logger?.log(
            "Deferred check already completed, returning cached attribution"
        )
        callback(Result.success(cached))
        return
    }

    // Mark that we tried, but do NOT consume the completion flag here — it is
    // set only when the server gives a definitive response (see
    // handleAttributionResponse), so an offline attempt retries next launch.
    if (storage.deferredCheckAttempted) {
        logger?.log("Retrying deferred check after a previous failed attempt")
    }
    storage.deferredCheckAttempted = true

    if (installReferrerReader != null) {
        logger?.log("First launch: trying Play Install Referrer")
        tryReferrerThenFallback(
            installReferrerReader, fingerprintCollector,
            apiClient, storage, logger, isCurrentCheck, settings, callback
        )
    } else {
        logger?.log("First launch: collecting device signals")
        collectAndMatch(
            fingerprintCollector, apiClient,
            storage, logger, isCurrentCheck, settings, callback
        )
    }
}

private fun tryReferrerThenFallback(
    reader: ReferrerSource,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    isCurrentCheck: () -> Boolean,
    settings: RetrySettings,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    reader.readReferrer { referrerResult ->
        val read = referrerResult.getOrNull()
        if (read != null) {
            logger?.log("Referrer found: ${read.linkId ?: "recovery token only"}")
            matchWithReferrer(
                read, fingerprintCollector, apiClient, storage,
                logger, isCurrentCheck, settings, callback
            )
        } else {
            logger?.log(
                "No WarpLink referrer, falling back to probabilistic matching"
            )
            collectAndMatch(
                fingerprintCollector, apiClient,
                storage, logger, isCurrentCheck, settings, callback
            )
        }
    }
}

private fun matchWithReferrer(
    read: ReferrerRead,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    isCurrentCheck: () -> Boolean,
    settings: RetrySettings,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    val routeMatches = read.isRoutable(System.currentTimeMillis() / 1000)
    // A recovery token is redeemed alone first: see redeemReferrerToken.
    redeemReferrerToken(
        read.recoveryToken, apiClient, storage, settings, isCurrentCheck
    ) { outcome ->
        when (outcome) {
            is ReferrerTokenOutcome.Matched -> handleAttributionResponse(
                outcome.response, storage, logger, isCurrentCheck,
                routeMatches = routeMatches, callback = callback
            )
            is ReferrerTokenOutcome.Retry -> callback(Result.failure(outcome.error))
            ReferrerTokenOutcome.FallThrough -> matchWithLinkId(
                read, fingerprintCollector, apiClient, storage,
                logger, isCurrentCheck, settings, routeMatches, callback
            )
        }
    }
}

private fun matchWithLinkId(
    read: ReferrerRead,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    isCurrentCheck: () -> Boolean,
    settings: RetrySettings,
    routeMatches: Boolean,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    // deviceId is left null on Android: there is no privacy-safe IDFV analog
    // (ANDROID_ID is disclaimed in the SDK privacy docs), and the Play Install
    // Referrer is the deterministic primary path here. The token is not sent
    // again: redeemReferrerToken already settled it.
    matchWithRetry(
        apiClient, MatchSlot.DEFERRED_CHECK, settings, isCurrentCheck,
        fingerprintCollector.collect(),
        referrer = read.linkId,
        isReinstall = storage.isReinstall
    ) { attrResult ->
        attrResult.onFailure { callback(Result.failure(it)) }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, storage, logger, isCurrentCheck,
                routeMatches = routeMatches, callback = callback
            )
        }
    }
}

private fun collectAndMatch(
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    isCurrentCheck: () -> Boolean,
    settings: RetrySettings,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    // Collection is total, so there is no pre-flight failure branch here any
    // more: a degraded signal set still produces a request, and a request that
    // misses is recorded server-side where an abandoned one is not.
    matchWithRetry(
        apiClient, MatchSlot.DEFERRED_CHECK, settings, isCurrentCheck,
        fingerprintCollector.collect(),
        referrer = null,
        isReinstall = storage.isReinstall
    ) { attrResult ->
        attrResult.onFailure { error ->
            callback(Result.failure(error))
        }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, storage, logger, isCurrentCheck, callback = callback
            )
        }
    }
}
