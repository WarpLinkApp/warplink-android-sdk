package app.warplink.internal

/**
 * Validates the API key against `/sdk/validate` (unless a recent success is
 * still cached) and applies the org's link domains so custom domains resolve.
 * Reports validity via [onResult]; a network failure is logged and leaves the
 * cached state untouched (no [onResult] call), so it retries next configure.
 */
internal fun performServerValidation(
    storage: Storage,
    apiClient: ApiKeyValidating,
    logger: Logger?,
    onResult: (Boolean) -> Unit
) {
    if (storage.isApiKeyValidationCacheValid) {
        logger?.log("API key validation cached, skipping")
        return
    }

    apiClient.validateApiKey { result ->
        result.onSuccess { response ->
            if (response.valid) {
                storage.apiKeyValidatedAt = System.currentTimeMillis()
                if (response.domains.isNotEmpty()) {
                    UriParser.setServerDomains(response.domains)
                    storage.cachedDomains = response.domains
                    logger?.log("Link domains loaded: ${response.domains}")
                }
                logger?.log("API key validated successfully")
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
