package app.warplink.internal

import android.app.Application
import android.content.Context
import android.net.Uri
import app.warplink.DeferredCheckStatus
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.WarpLinkOptions
import app.warplink.BuildConfig
import app.warplink.internal.referral.ReferralContext
import app.warplink.internal.referral.ReferralEntry

/**
 * The state behind the public `WarpLink` object: the current session, the
 * deferred-check coordinator and the lock that guards them. The object keeps
 * the documented API and forwards here.
 */
internal class WarpLinkRuntime {

    private val lock = Any()
    private var session: SdkSession? = null
    private val deferredChecks = DeferredCheckCoordinator()
    private val launcher = DeferredCheckLauncher(deferredChecks, { synchronized(lock) { session } }, { settings })

    /** Retry settings for the funnels this runtime drives, overridden only by tests. */
    var retrySettingsOverride: RetrySettings? = null

    val isConfigured: Boolean
        get() = synchronized(lock) { session != null }

    val attributionResult: WarpLinkDeepLink?
        get() = synchronized(lock) { session?.storage?.cachedAttribution }

    val tetherTestReceipt: String?
        get() = synchronized(lock) { session?.storage?.referral?.tetherTestReceipt }

    val isAttributionComplete: Boolean
        get() = synchronized(lock) { session?.storage?.deferredCheckCompleted ?: false }

    val deferredCheckStatus: DeferredCheckStatus
        get() {
            val storage = synchronized(lock) { session?.storage } ?: return DeferredCheckStatus.NotChecked
            val running = deferredChecks.isRunning || launcher.hasWaitingCheck
            return deferredCheckStatusOf(storage, running, System.currentTimeMillis())
        }

    private val settings: RetrySettings
        get() = retrySettingsOverride ?: RetrySettings.DEFAULT

    fun configure(context: Context, apiKey: String, options: WarpLinkOptions) {
        val appContext = context.applicationContext
        if (!KEY_REGEX.matches(apiKey)) {
            Logger(options.debugLogging).warn(
                "Invalid API key format; WarpLink not configured. " +
                    "Expected wl_(live|test)_<32 alphanumeric characters>."
            )
            options.onLink?.invoke(Result.failure(WarpLinkError.InvalidApiKeyFormat))
            return
        }
        val live = synchronized(lock) {
            // The same key and options again would rebuild an identical session and
            // abandon the check it is running, so the running one stays. Only the
            // new call's onLink replaces the old one.
            session?.takeIf { it.isConfiguredWith(apiKey, options) }?.let {
                it.adoptSink(options)
                return
            }
            session?.let(::retire)
            SdkSession.create(appContext, apiKey, options, ::resolveForCallback).also {
                session = it
                applyLinkDomains(appContext, options, it)
            }
        }
        // Reconfiguring abandons any in-flight check. Its parked callers are
        // answered with success(null), never a fabricated NotConfigured.
        deferredChecks.abandon()
        // An abandoned caller may have reconfigured. The newer configure then
        // runs the remaining steps for its own session, so this one stops.
        if (synchronized(lock) { session !== live }) return
        (appContext as? Application)?.let(launcher::attach)
        startSession(appContext, apiKey, options, live)
    }

    private fun startSession(appContext: Context, apiKey: String, options: WarpLinkOptions, live: SdkSession) {
        live.logger.log("Configured with API key: ${Logger.maskApiKey(apiKey)}")
        live.logger.log("API endpoint: ${options.apiEndpoint}")
        live.logger.log("WarpLink SDK configured (v${BuildConfig.SDK_VERSION})")
        logBackupCaveat(appContext, live.logger)
        performServerValidation(live.storage, live.apiClient, live.logger, ifCurrent = { apply ->
            synchronized(lock) { if (session === live) apply() }
        }) {}
        live.startAutomaticHandling(::checkDeferred) { dispatchAutoDeferred(live.autoHandler, it) }
        val handler = synchronized(lock) { session?.autoHandler }
        ReferralEntry.resume(referralContext()) { dispatchAutoDeferred(handler, it) }
    }

    /**
     * Declares the domains of [live] in the same lock hold that made it current,
     * so an older configure cannot overwrite a newer one's. Always replaced, even
     * when empty, so the domains of a previous key never outlive it.
     */
    private fun applyLinkDomains(context: Context, options: WarpLinkOptions, live: SdkSession) {
        applyDeclaredLinkDomains(context, options.linkDomains, live.logger)
        UriParser.setServerDomains(live.storage.cachedDomains)
    }

    fun clearAttribution(): Result<Unit> {
        val outcome = synchronized(lock) { clearTestAttribution(session, deferredChecks) }
        outcome.notifyWaiting()
        outcome.restart?.let { restarted ->
            // A callback answered above may have reconfigured. The restart belongs
            // to the Test session that was cleared and never runs on another.
            restarted.fireAutomaticDeferredCheck({ launcher.checkIfCurrent(restarted, it) }) {
                dispatchAutoDeferred(restarted.autoHandler, it)
            }
        }
        return outcome.result
    }

    fun handleDeepLink(uri: Uri, callback: (Result<WarpLinkDeepLink>) -> Unit) {
        val handler = synchronized(lock) { session?.autoHandler }
        if (handler != null && UriParser.isWarpLinkUri(uri)) {
            handler.dispatchManual(uri, callback)
            return
        }
        resolveForCallback(uri, callback)
    }

    fun onNewIntent(data: Uri?) {
        val current = synchronized(lock) { session }
        val handler = current?.takeIf { it.options.automaticDeepLinks }?.autoHandler ?: run {
            // The documented integration is an unconditional one-liner, so a host
            // that turned the flag off in shared config would otherwise watch
            // warm-start links vanish with no explanation.
            current?.logger?.log("onNewIntent ignored: automatic deep link handling is off")
            return
        }
        data?.let { handler.dispatch(it) }
    }

    fun checkDeferred(callback: (Result<WarpLinkDeepLink?>) -> Unit) = launcher.check(callback)

    fun referralContext(): ReferralContext? = synchronized(lock) {
        val current = session ?: return null
        ReferralContext(
            apiClient = current.apiClient,
            storage = current.storage,
            logger = current.logger,
            settings = settings,
            onMatch = { deferredChecks.stageMatch(current.storage, it) }
        )
    }

    private fun resolveForCallback(uri: Uri, callback: (Result<WarpLinkDeepLink>) -> Unit) {
        val current = synchronized(lock) { session }
        if (current == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }
        resolveDeepLink(current.apiClient, current.logger, uri, settings = settings, callback = callback)
    }

    /**
     * Routes an auto-fired deferred result to [handler], but only while it is
     * still the current sink. A check abandoned by a reconfigure stays silent to
     * the host, so one install never navigates twice.
     */
    private fun dispatchAutoDeferred(handler: AutoLinkHandler?, result: Result<WarpLinkDeepLink?>) {
        val current = synchronized(lock) { session }
        if (current?.autoHandler === handler) {
            handler?.dispatchDeferred(result)
        } else {
            current?.logger?.log("Deferred check abandoned; not dispatching to the superseded onLink")
        }
    }

    /** Stops the session's handler and retires its referral redeems. The caller holds [lock]. */
    private fun retire(previous: SdkSession) {
        previous.autoHandler?.unregister()
        synchronized(previous.storage.stateLock) { previous.storage.referral.orphanRedeems() }
    }

    fun reset() {
        synchronized(lock) {
            session?.let(::retire)
            session = null
            retrySettingsOverride = null
        }
        deferredChecks.reset()
        launcher.reset()
        UriParser.resetKnownDomains()
    }

    private companion object {
        val KEY_REGEX = Regex("^wl_(live|test)_[a-zA-Z0-9]{32}$")
    }
}
