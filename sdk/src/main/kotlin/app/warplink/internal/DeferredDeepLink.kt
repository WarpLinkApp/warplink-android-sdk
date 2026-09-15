package app.warplink.internal

import app.warplink.WarpLinkDeepLink

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
    cancelInFlightAttributionMatch()

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
            logger?.log("Referrer found: ${read.linkId}")
            matchWithReferrer(
                read, fingerprintCollector, apiClient, storage,
                logger, isCurrentCheck, settings, callback
            )
        } else {
            logger?.log(
                "No WarpLink referrer, falling back to fingerprint"
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
    // deviceId is left null on Android: there is no privacy-safe IDFV analog
    // (ANDROID_ID is disclaimed in the SDK privacy docs), and the Play Install
    // Referrer is the deterministic primary path here.
    matchWithRetry(
        apiClient, settings, isCurrentCheck,
        fingerprintCollector.collect(),
        referrer = read.linkId,
        isReinstall = storage.isReinstall
    ) { attrResult ->
        attrResult.onFailure { callback(Result.failure(it)) }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, storage, logger, isCurrentCheck,
                routeMatches = read.isRoutable(System.currentTimeMillis() / 1000),
                callback = callback
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
        apiClient, settings, isCurrentCheck,
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

private fun handleAttributionResponse(
    response: AttributionResponse,
    storage: Storage,
    logger: Logger?,
    isCurrentCheck: () -> Boolean,
    routeMatches: Boolean = true,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    if (handleIfAbandoned(isCurrentCheck, logger, callback)) return
    if (response.matched && !routeMatches) {
        settleWithoutRouting(storage, logger, callback)
        return
    }
    val deepLink = routableDeepLink(response)

    // A confirmed no-match settles attribution: the server looked and found
    // nothing. A match with nothing to route to does not, whatever the server
    // called it. It is discarded above and the host is told nothing matched, so
    // completing the check on it spends this install's one attempt on an answer
    // nobody can act on, and nothing would retry: deferredCheckCompleted
    // outlives every relaunch and only a reinstall clears it.
    if (deepLink == null && response.matched) {
        logger?.log(
            "Attribution matched with nothing to route to, retrying next launch"
        )
        callback(Result.success(null))
        return
    }

    storage.deferredCheckCompleted = true
    storage.cachedAttribution = deepLink
    if (deepLink != null) {
        logger?.log("Deferred deep link matched: ${deepLink.linkId}")
    } else {
        logger?.log("No deferred deep link match")
    }
    callback(Result.success(deepLink))
}
