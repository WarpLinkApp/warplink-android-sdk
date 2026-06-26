package app.warplink

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.InstallReferrerReader
import app.warplink.internal.Logger
import app.warplink.internal.Storage
import app.warplink.internal.UriParser
import app.warplink.internal.performDeferredCheck

object WarpLink {

    /**
     * The SDK version. Sourced from the build's VERSION_NAME (the published
     * Maven coordinate) via [BuildConfig] — do not replace this with a hardcoded
     * literal. Keeping it generated guarantees the User-Agent and attribution
     * `sdk_version` always match the released version, with nothing to bump by
     * hand at release time beyond the git tag.
     */
    val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private val lock = Any()
    private var apiKey: String? = null
    private var options: WarpLinkOptions? = null
    private var logger: Logger? = null
    private var storage: Storage? = null
    private var apiClient: ApiClient? = null
    private var fingerprintCollector: FingerprintCollector? = null
    private var installReferrerReader: InstallReferrerReader? = null
    private var isApiKeyValid: Boolean? = null

    val isConfigured: Boolean
        get() = synchronized(lock) { apiKey != null }

    /** Cached attribution result from the last deferred deep link check, or null if none. */
    val attributionResult: WarpLinkDeepLink?
        get() = synchronized(lock) { storage?.cachedAttribution }

    fun configure(
        context: Context,
        apiKey: String,
        options: WarpLinkOptions = WarpLinkOptions()
    ) {
        val keyRegex = Regex("^wl_(live|test)_[a-zA-Z0-9]{32}$")
        if (!keyRegex.matches(apiKey)) {
            throw WarpLinkError.InvalidApiKeyFormat
        }

        val appContext = context.applicationContext

        synchronized(lock) {
            this.apiKey = apiKey
            this.options = options
            this.logger = Logger(options.debugLogging)
            this.storage = Storage(appContext)
            this.apiClient = ApiClient(apiKey, options.apiEndpoint)
            this.fingerprintCollector = FingerprintCollector(appContext)
            this.installReferrerReader = InstallReferrerReader(appContext)
            this.isApiKeyValid = null
        }

        logger?.log(
            "Configured with API key: ${Logger.maskApiKey(apiKey)}"
        )
        logger?.log("API endpoint: ${options.apiEndpoint}")
        logger?.log("Match window: ${options.matchWindowHours} hours")
        logger?.log("WarpLink SDK configured (v$SDK_VERSION)")

        performServerValidation()
    }

    fun handleDeepLink(
        uri: Uri,
        callback: (Result<WarpLinkDeepLink>) -> Unit
    ) {
        if (!isConfigured) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }

        if (!UriParser.isWarpLinkUri(uri)) {
            callback(Result.failure(WarpLinkError.InvalidUrl))
            return
        }

        val slug = UriParser.extractSlug(uri)
        if (slug == null) {
            callback(Result.failure(WarpLinkError.InvalidUrl))
            return
        }

        val domain = UriParser.extractDomain(uri)

        val capturedApiClient: ApiClient
        val capturedLogger: Logger?
        synchronized(lock) {
            capturedApiClient = apiClient ?: return
            capturedLogger = logger
        }

        capturedLogger?.log("Resolving deep link: $slug@$domain")

        capturedApiClient.resolveLink(slug, domain) { result ->
            result.onSuccess { response ->
                val deepLink = WarpLinkDeepLink(
                    linkId = response.id,
                    destination = response.destinationUrl,
                    deepLinkUrl = response.androidUrl,
                    customParams = response.customParams,
                    isDeferred = false,
                    matchType = MatchType.DETERMINISTIC,
                    matchConfidence = 1.0
                )
                capturedLogger?.log(
                    "Deep link resolved: ${response.id}"
                )
                callback(Result.success(deepLink))
            }
            result.onFailure { error ->
                capturedLogger?.log(
                    "Deep link resolution failed: ${error.message}"
                )
                callback(Result.failure(error))
            }
        }
    }

    fun checkDeferredDeepLink(
        callback: (Result<WarpLinkDeepLink?>) -> Unit
    ) {
        if (!isConfigured) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }

        val capturedStorage: Storage
        val capturedFingerprintCollector: FingerprintCollector
        val capturedApiClient: ApiClient
        val capturedInstallReferrerReader: InstallReferrerReader?
        val capturedLogger: Logger?
        synchronized(lock) {
            capturedStorage = storage ?: return
            capturedFingerprintCollector = fingerprintCollector ?: return
            capturedApiClient = apiClient ?: return
            capturedInstallReferrerReader = installReferrerReader
            capturedLogger = logger
        }

        performDeferredCheck(
            capturedStorage,
            capturedFingerprintCollector,
            capturedApiClient,
            capturedInstallReferrerReader,
            capturedLogger,
            callback
        )
    }

    private fun performServerValidation() {
        val currentStorage = storage ?: return
        val currentApiClient = apiClient ?: return
        val currentLogger = logger

        if (currentStorage.isApiKeyValidationCacheValid) {
            currentLogger?.log(
                "API key validation cached, skipping"
            )
            return
        }

        currentApiClient.validateApiKey { result ->
            result.onSuccess { valid ->
                synchronized(lock) {
                    isApiKeyValid = valid
                    if (valid) {
                        storage?.apiKeyValidatedAt =
                            System.currentTimeMillis()
                        currentLogger?.log(
                            "API key validated successfully"
                        )
                    } else {
                        currentLogger?.log(
                            "API key validation failed: key rejected"
                        )
                    }
                }
            }
            result.onFailure { error ->
                currentLogger?.log(
                    "API key validation error: ${error.message}"
                )
            }
        }
    }

    @VisibleForTesting
    internal fun reset() {
        synchronized(lock) {
            apiKey = null
            options = null
            logger = null
            storage = null
            apiClient = null
            fingerprintCollector = null
            installReferrerReader = null
            isApiKeyValid = null
        }
    }
}
