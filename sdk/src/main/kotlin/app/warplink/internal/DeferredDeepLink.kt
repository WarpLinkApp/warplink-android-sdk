package app.warplink.internal

import app.warplink.MatchType
import app.warplink.WarpLink
import app.warplink.WarpLinkDeepLink

internal fun performDeferredCheck(
    storage: Storage,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    installReferrerReader: InstallReferrerReader?,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    if (!storage.isFirstLaunch) {
        val cached = storage.cachedAttribution
        logger?.log(
            "Not first launch, returning cached attribution"
        )
        callback(Result.success(cached))
        return
    }

    storage.isFirstLaunch = false

    if (installReferrerReader != null) {
        logger?.log("First launch — trying Play Install Referrer")
        tryReferrerThenFallback(
            installReferrerReader, fingerprintCollector,
            apiClient, storage, logger, callback
        )
    } else {
        logger?.log("First launch — collecting device signals")
        collectAndMatch(
            fingerprintCollector, apiClient,
            storage, logger, callback
        )
    }
}

private fun tryReferrerThenFallback(
    reader: InstallReferrerReader,
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    reader.readReferrer { referrerResult ->
        val linkId = referrerResult.getOrNull()
        if (linkId != null) {
            logger?.log("Referrer found: $linkId")
            matchWithReferrer(
                linkId, apiClient, storage, logger, callback
            )
        } else {
            logger?.log(
                "No WarpLink referrer, falling back to fingerprint"
            )
            collectAndMatch(
                fingerprintCollector, apiClient,
                storage, logger, callback
            )
        }
    }
}

private fun matchWithReferrer(
    linkId: String,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    apiClient.matchAttribution(
        null, WarpLink.SDK_VERSION, null, referrer = linkId
    ) { attrResult ->
        attrResult.onFailure { callback(Result.failure(it)) }
        attrResult.onSuccess { response ->
            handleAttributionResponse(
                response, storage, logger, callback
            )
        }
    }
}

private fun collectAndMatch(
    fingerprintCollector: FingerprintCollector,
    apiClient: ApiClient,
    storage: Storage,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    fingerprintCollector.collectFingerprint { signalResult ->
        signalResult.onFailure { error ->
            callback(Result.failure(error))
            return@collectFingerprint
        }
        signalResult.onSuccess { signals ->
            apiClient.matchAttribution(
                signals, WarpLink.SDK_VERSION, null
            ) { attrResult ->
                attrResult.onFailure { error ->
                    callback(Result.failure(error))
                }
                attrResult.onSuccess { response ->
                    handleAttributionResponse(
                        response, storage, logger, callback
                    )
                }
            }
        }
    }
}

private fun handleAttributionResponse(
    response: AttributionResponse,
    storage: Storage,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    if (!response.matched ||
        response.linkId == null ||
        response.destinationUrl == null
    ) {
        callback(Result.success(null))
        return
    }
    val matchType = when (response.matchType?.lowercase()) {
        "deterministic" -> MatchType.DETERMINISTIC
        "probabilistic" -> MatchType.PROBABILISTIC
        else -> null
    }
    val deepLink = WarpLinkDeepLink(
        linkId = response.linkId,
        destination = response.destinationUrl,
        deepLinkUrl = response.deepLinkUrl,
        customParams = response.customParams ?: emptyMap(),
        isDeferred = true,
        matchType = matchType,
        matchConfidence = response.matchConfidence
    )
    storage.cachedAttribution = deepLink
    logger?.log(
        "Deferred deep link matched: ${response.linkId}"
    )
    callback(Result.success(deepLink))
}
