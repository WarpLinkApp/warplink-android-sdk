package app.warplink.internal

/**
 * Validates the API key against `/sdk/validate` (unless a recent success is
 * still cached) and applies the org's link domains so custom domains resolve.
 * Reports validity via [onResult]; a network failure is logged and leaves the
 * cached state untouched (no [onResult] call), so it retries next configure.
 *
 * [ifCurrent] runs the writes of an answer only while the session that asked is
 * still the SDK's current one, so a late answer from a previous key (a live key
 * before a switch to Test, say) never replaces the new key's link domains.
 */
internal fun performServerValidation(
    storage: Storage,
    apiClient: ApiKeyValidating,
    logger: Logger?,
    ifCurrent: (() -> Unit) -> Unit = { it() },
    onResult: (Boolean) -> Unit
) {
    if (storage.isApiKeyValidationCacheValid) {
        logger?.log("API key validation cached, skipping")
        return
    }

    apiClient.validateApiKey { result ->
        result.onSuccess { response ->
            if (response.valid) {
                ifCurrent { applyValidation(storage, response.domains, logger) }
            } else {
                logger?.warn(
                    "WarpLink API key was rejected by the server; deep links " +
                        "and attribution will not work. Replace it with an " +
                        "active SDK key from your dashboard."
                )
            }
            onResult(response.valid)
        }
        result.onFailure { error ->
            logger?.log("API key validation error: ${error.message}")
        }
    }
}

private fun applyValidation(storage: Storage, domains: List<String>, logger: Logger?) {
    storage.apiKeyValidatedAt = System.currentTimeMillis()
    if (domains.isNotEmpty()) {
        UriParser.setServerDomains(domains)
        storage.cachedDomains = domains
        logger?.log("Link domains loaded: $domains")
    }
    logger?.log("API key validated successfully")
}
