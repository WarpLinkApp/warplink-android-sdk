package app.warplink

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.annotation.VisibleForTesting
import app.warplink.internal.ApiClient
import app.warplink.internal.AutoLinkHandler
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.InstallReferrerReader
import app.warplink.internal.Logger
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.UriParser
import app.warplink.internal.applyDeclaredLinkDomains
import app.warplink.internal.performDeferredCheck
import app.warplink.internal.performServerValidation
import app.warplink.internal.resolveDeepLink
import java.util.concurrent.atomic.AtomicBoolean

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
    private var autoHandler: AutoLinkHandler? = null
    private val deferredCheckInFlight = AtomicBoolean(false)

    /**
     * Callbacks that arrived while a deferred check was already running. They are
     * drained with the in-flight check's own result, so a manual caller never
     * loses a first-launch match to the automatic check that ran before it.
     * Guarded by [lock].
     */
    private val pendingDeferredCallbacks =
        mutableListOf<(Result<WarpLinkDeepLink?>) -> Unit>()

    /**
     * Bumped whenever an in-flight deferred check is abandoned (reconfigure,
     * reset). A check captures the value at start and compares it on completion,
     * so a stale response answers only its own caller instead of clearing the
     * replacement's in-flight flag or draining its parked callbacks, and applies
     * nothing to storage (see `performDeferredCheck`'s `isCurrentCheck`).
     * Guarded by [lock].
     */
    private var deferredCheckGeneration = 0

    /**
     * Retry settings for the two funnels this facade drives, overridden only by tests.
     *
     * `internal`, so it is invisible to consumers, and nullable so the shipped default is
     * the absence of an override rather than a copy of it. A facade-level test that needs
     * a retry to actually happen sets `RetrySettings.NO_WAIT` here, because the retry's
     * wait is a `postDelayed` and a test that does not move the clock would otherwise wait
     * for a timer that never comes due. Cleared by [reset] so one test cannot leak it into
     * the next.
     */
    @VisibleForTesting
    internal var retrySettingsOverride: RetrySettings? = null

    val isConfigured: Boolean
        get() = synchronized(lock) { apiKey != null }

    /** Cached attribution result from the last deferred deep link check, or null if none. */
    val attributionResult: WarpLinkDeepLink?
        get() = synchronized(lock) { storage?.cachedAttribution }

    /**
     * Whether the deferred attribution check has definitively completed for this
     * install. The React Native bridge reads this so a deferred match reaches
     * `onLink` at most once across launches, matching the native auto-dispatch guard.
     */
    fun isAttributionComplete(): Boolean =
        synchronized(lock) { storage?.deferredCheckCompleted ?: false }

    /**
     * Configure the SDK. With a bare call the SDK automatically handles
     * cold-start deep links and fires the deferred check; provide
     * [WarpLinkOptions.onLink] to receive results. Safe to call from
     * `Application.onCreate()`: a malformed key does NOT throw — it is reported
     * via [WarpLinkOptions.onLink] / a warning log and the SDK stays unconfigured.
     */
    fun configure(
        context: Context,
        apiKey: String,
        options: WarpLinkOptions = WarpLinkOptions()
    ) {
        val appContext = context.applicationContext
        if (!KEY_REGEX.matches(apiKey)) {
            Logger(options.debugLogging).warn(
                "Invalid API key format; WarpLink not configured. " +
                    "Expected wl_(live|test)_<32 alphanumeric characters>."
            )
            options.onLink?.invoke(Result.failure(WarpLinkError.InvalidApiKeyFormat))
            return
        }

        synchronized(lock) {
            autoHandler?.unregister()
            this.apiKey = apiKey
            this.options = options
            this.logger = Logger(options.debugLogging)
            this.storage = Storage(appContext)
            this.apiClient =
                ApiClient(apiKey, options.apiEndpoint, appContext.packageName)
            this.fingerprintCollector = FingerprintCollector()
            this.installReferrerReader =
                InstallReferrerReader(appContext, this.logger)
            this.isApiKeyValid = null
            this.autoHandler = options.onLink?.let { sink ->
                AutoLinkHandler(
                    application = appContext as? Application,
                    logger = this.logger,
                    isWarpLinkUri = UriParser::isWarpLinkUri,
                    onLink = sink,
                    resolve = ::resolveForCallback
                )
            }
        }
        // Reconfiguring abandons any in-flight check, so callers parked on it
        // must be answered here. Leaving them queued would let the abandoned
        // check deliver its result to callers that parked on a later one.
        // They are answered with success(null), never a fabricated
        // NotConfigured: the SDK is configured, and iOS answers the same way.
        val abandoned = synchronized(lock) {
            deferredCheckGeneration++
            deferredCheckInFlight.set(false)
            val snapshot = pendingDeferredCallbacks.toList()
            pendingDeferredCallbacks.clear()
            snapshot
        }
        abandoned.forEach { it(Result.success(null)) }

        // Synchronous, and before automatic handling starts: a cold-start
        // intent is already waiting by the time configure() runs.
        applyDeclaredLinkDomains(appContext, options.linkDomains, logger)

        val currentStorage = synchronized(lock) { storage }
        currentStorage?.cachedDomains?.let {
            if (it.isNotEmpty()) UriParser.setServerDomains(it)
        }

        logger?.log("Configured with API key: ${Logger.maskApiKey(apiKey)}")
        logger?.log("API endpoint: ${options.apiEndpoint}")
        logger?.log("WarpLink SDK configured (v$SDK_VERSION)")
        logBackupCaveat(appContext)

        runServerValidation()
        startAutomaticHandling()
    }

    /**
     * Resolve an App Link URI. Optional in the opt-out model — cold start is
     * automatic — but available for manual/advanced integrations. The result is
     * deduped against automatic dispatch of the same URI: when the automatic
     * path already claimed this URI, [callback] is answered from that dispatch's
     * result instead of resolving it a second time.
     */
    fun handleDeepLink(
        uri: Uri,
        callback: (Result<WarpLinkDeepLink>) -> Unit
    ) {
        val handler = synchronized(lock) { autoHandler }
        if (handler != null && isWarpLinkUri(uri)) {
            handler.dispatchManual(uri, callback)
            return
        }
        resolveForCallback(uri, callback)
    }

    /**
     * Whether [uri] is a WarpLink link this SDK would claim: a host in the known
     * link-domain set AND a path that carries a slug (exactly one segment).
     *
     * This is the check the automatic path already makes, exposed. The automatic
     * dispatcher asks it before it touches anything else, so a URI it refuses
     * never reaches the dedupe window, never claims a tap, and never supersedes
     * a link still resolving: `AutoLinkHandler.dispatch` returns on a false
     * answer before `claimLocked`. [handleDeepLink] routes on the same answer.
     *
     * Exposed because a host that forwards every incoming URI, an app with its
     * own router or the React Native bridge, has to ask the same question at the
     * same point. Learning it afterwards from a [WarpLinkError.InvalidUrl]
     * rejection is too late to undo state a claim has already changed.
     *
     * Pure: it reads the known-domain set and returns. It resolves nothing,
     * claims nothing, and touches no network.
     *
     * Before [configure] the known set is the default domain alone, `aplnk.to`,
     * which is what the parser starts with and falls back to. A link on a custom
     * domain therefore reads as false until [configure] has declared it (through
     * [WarpLinkOptions.linkDomains] or the manifest entry) or `/sdk/validate`
     * has returned it.
     */
    fun isWarpLinkUri(uri: Uri): Boolean = UriParser.isWarpLinkUri(uri)

    /**
     * Single reader for [WarpLinkOptions.automaticDeepLinks], so the
     * cold-start gate and the warm-start gate cannot drift apart. They already
     * did once: cold start honoured the flag and warm start did not, which let
     * an app that had opted out still receive automatic dispatch.
     *
     * False before [configure] runs: an unconfigured SDK dispatches nothing.
     */
    private val automaticDeepLinksEnabled: Boolean
        get() = synchronized(lock) { options?.automaticDeepLinks == true }

    /**
     * Warm-start bridge for the automatic model: call from your Activity's
     * `onNewIntent` so links that arrive while your task is already running
     * reach [WarpLinkOptions.onLink]. Requires `launchMode="singleTask"` (or
     * `singleTop`) on the Activity.
     *
     * No-op when [WarpLinkOptions.automaticDeepLinks] is false or no `onLink`
     * sink is configured. An app that opted out routes warm-start links the
     * same way it routes cold-start ones: itself, via [handleDeepLink].
     */
    fun onNewIntent(intent: Intent) {
        val handler = synchronized(lock) {
            if (automaticDeepLinksEnabled) autoHandler else null
        } ?: run {
            // Saying nothing here is the failure mode this model exists to
            // avoid. The documented integration is an unconditional one-liner,
            // so a host that turned the flag off in shared config would
            // otherwise watch warm-start links vanish with no explanation.
            logger?.log(
                "onNewIntent ignored: automatic deep link handling is off"
            )
            return
        }
        intent.data?.let { handler.dispatch(it) }
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
        val capturedSettings: RetrySettings
        synchronized(lock) {
            capturedStorage = storage ?: return
            capturedFingerprintCollector = fingerprintCollector ?: return
            capturedApiClient = apiClient ?: return
            capturedInstallReferrerReader = installReferrerReader
            capturedLogger = logger
            capturedSettings = retrySettingsOverride ?: RetrySettings.DEFAULT
        }

        if (capturedStorage.deferredCheckCompleted) {
            capturedLogger?.log("Deferred check already completed, returning cached")
            callback(Result.success(capturedStorage.cachedAttribution))
            return
        }
        var generation = 0
        val startsCheck = synchronized(lock) {
            if (deferredCheckInFlight.compareAndSet(false, true)) {
                generation = deferredCheckGeneration
                true
            } else {
                pendingDeferredCallbacks.add(callback)
                false
            }
        }
        if (!startsCheck) {
            capturedLogger?.log("Deferred check already in flight, awaiting its result")
            return
        }

        performDeferredCheck(
            capturedStorage,
            capturedFingerprintCollector,
            capturedApiClient,
            capturedInstallReferrerReader,
            capturedLogger,
            isCurrentCheck = {
                synchronized(lock) { generation == deferredCheckGeneration }
            },
            settings = capturedSettings
        ) { result ->
            val pending = synchronized(lock) {
                if (generation != deferredCheckGeneration) {
                    // Abandoned by a reconfigure: its parked callers were already
                    // answered there, so touching the shared state now would
                    // release the replacement check and steal its callbacks.
                    emptyList()
                } else {
                    deferredCheckInFlight.set(false)
                    val snapshot = pendingDeferredCallbacks.toList()
                    pendingDeferredCallbacks.clear()
                    snapshot
                }
            }
            callback(result)
            pending.forEach { it(result) }
        }
    }

    /**
     * The device-seen marker lives in SharedPreferences and outlives an
     * uninstall only because Auto Backup restores it. With backup off, a
     * reinstall on this device is reported as a first install. Attribution
     * itself is unaffected, so this is a debug line rather than a warning.
     */
    private fun logBackupCaveat(appContext: Context) {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) {
            logger?.log(
                "Auto Backup is disabled (android:allowBackup=false): a reinstall on " +
                    "this device will be reported as a first install"
            )
        }
    }

    private fun resolveForCallback(
        uri: Uri,
        callback: (Result<WarpLinkDeepLink>) -> Unit
    ) {
        val client: ApiClient
        val log: Logger?
        val settings: RetrySettings
        synchronized(lock) {
            client = apiClient ?: run {
                callback(Result.failure(WarpLinkError.NotConfigured))
                return
            }
            log = logger
            settings = retrySettingsOverride ?: RetrySettings.DEFAULT
        }
        resolveDeepLink(
            client,
            log,
            uri,
            settings = settings,
            callback = callback
        )
    }

    private fun runServerValidation() {
        val currentStorage: Storage
        val currentApiClient: ApiClient
        val currentLogger: Logger?
        synchronized(lock) {
            currentStorage = storage ?: return
            currentApiClient = apiClient ?: return
            currentLogger = logger
        }
        performServerValidation(currentStorage, currentApiClient, currentLogger) { valid ->
            synchronized(lock) { isApiKeyValid = valid }
        }
    }

    /**
     * One snapshot, so the two gates and the handler cannot come from different
     * `configure` calls. This runs last in [configure], and [configure] answers
     * an abandoned deferred caller inline before reaching it, so a host that
     * reconfigures from that answer replaces all three behind this call. Taking
     * no parameter is deliberate: one named `options` would shadow the field the
     * [automaticDeepLinksEnabled] accessor reads, which is how the two gates
     * came to be read from two different configurations.
     */
    private fun startAutomaticHandling() {
        val enabled: Boolean
        val deferred: Boolean
        val handler: AutoLinkHandler?
        synchronized(lock) {
            enabled = automaticDeepLinksEnabled
            deferred = options?.automaticDeferredDeepLinks == true
            handler = autoHandler
        }
        if (enabled) {
            if (handler != null) {
                handler.registerColdStart()
                logger?.log("Automatic cold-start deep link handling enabled")
            } else {
                logger?.log("automaticDeepLinks enabled but no onLink set; nothing to dispatch to")
            }
        }
        if (deferred) {
            // Deliver a completed deferred result at most once. Once the check
            // has completed, a cached match stays readable via attributionResult
            // but must NOT be re-pushed through onLink on every later launch
            // (mirrors iOS runAutoDeferredCheck's !isAttributionComplete guard).
            val alreadyCompleted =
                synchronized(lock) { storage?.deferredCheckCompleted ?: false }
            if (alreadyCompleted) {
                logger?.log("Deferred check already completed; not re-dispatching to onLink")
            } else {
                // Fires with or without a sink: a bare configure(context, apiKey) is
                // documented to attribute the install automatically, and the match is
                // recorded server-side and cached locally either way. A manual caller
                // that races this one is coalesced onto it rather than starved.
                logger?.log("Auto-firing deferred deep link check")
                checkDeferredDeepLink { result ->
                    dispatchAutoDeferred(handler, result)
                }
            }
        }
    }

    /**
     * Route an auto-fired deferred result to [handler], but only while it is
     * still the SDK's current sink. A check abandoned by a reconfigure stays
     * silent to the host: routing its result to the previous configure's `onLink`
     * would either navigate twice for one install (the replacement check
     * dispatches to the new sink) or push a spurious failure at a host that
     * merely reconfigured.
     */
    private fun dispatchAutoDeferred(
        handler: AutoLinkHandler?,
        result: Result<WarpLinkDeepLink?>
    ) {
        if (synchronized(lock) { autoHandler === handler }) {
            handler?.dispatchDeferred(result)
        } else {
            logger?.log(
                "Deferred check abandoned; not dispatching to the superseded onLink"
            )
        }
    }

    @VisibleForTesting
    internal fun reset() {
        synchronized(lock) {
            autoHandler?.unregister()
            autoHandler = null
            apiKey = null
            options = null
            logger = null
            storage = null
            apiClient = null
            fingerprintCollector = null
            installReferrerReader = null
            isApiKeyValid = null
            retrySettingsOverride = null
            pendingDeferredCallbacks.clear()
            deferredCheckGeneration++
        }
        deferredCheckInFlight.set(false)
        UriParser.resetKnownDomains()
    }

    private val KEY_REGEX = Regex("^wl_(live|test)_[a-zA-Z0-9]{32}$")
}
