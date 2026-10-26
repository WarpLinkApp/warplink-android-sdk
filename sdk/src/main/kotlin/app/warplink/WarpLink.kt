package app.warplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import app.warplink.internal.AutoLinkHandler
import app.warplink.internal.DeferredCheckCoordinator
import app.warplink.internal.Logger
import app.warplink.internal.RetrySettings
import app.warplink.internal.SdkSession
import app.warplink.internal.UriParser
import app.warplink.internal.applyDeclaredLinkDomains
import app.warplink.internal.logBackupCaveat
import app.warplink.internal.performServerValidation
import app.warplink.internal.referral.ReferralContext
import app.warplink.internal.referral.ReferralEntry
import app.warplink.internal.resolveDeepLink
import app.warplink.internal.startAutomaticHandling

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
    private var session: SdkSession? = null
    private val deferredChecks = DeferredCheckCoordinator()

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
        get() = synchronized(lock) { session != null }

    /** Cached attribution result from the last deferred deep link check, or null if none. */
    val attributionResult: WarpLinkDeepLink?
        get() = synchronized(lock) { session?.storage?.cachedAttribution }

    /**
     * Whether the deferred attribution check has definitively completed for this
     * install. The React Native bridge reads this so a deferred match reaches
     * `onLink` at most once across launches, matching the native auto-dispatch guard.
     */
    fun isAttributionComplete(): Boolean =
        synchronized(lock) { session?.storage?.deferredCheckCompleted ?: false }

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
            session?.autoHandler?.unregister()
            session = SdkSession.create(appContext, apiKey, options, ::resolveForCallback)
        }
        // Reconfiguring abandons any in-flight check. Its parked callers are
        // answered with success(null), never a fabricated NotConfigured: the SDK
        // is configured, and iOS answers the same way.
        deferredChecks.abandon()

        // An abandoned caller may have reconfigured, so the steps below read the
        // session that is current now.
        val live = synchronized(lock) { session } ?: return

        // Synchronous, and before automatic handling starts: a cold-start
        // intent is already waiting by the time configure() runs.
        applyDeclaredLinkDomains(appContext, options.linkDomains, live.logger)
        live.storage.cachedDomains?.let {
            if (it.isNotEmpty()) UriParser.setServerDomains(it)
        }

        live.logger.log("Configured with API key: ${Logger.maskApiKey(apiKey)}")
        live.logger.log("API endpoint: ${options.apiEndpoint}")
        live.logger.log("WarpLink SDK configured (v$SDK_VERSION)")
        logBackupCaveat(appContext, live.logger)

        performServerValidation(live.storage, live.apiClient, live.logger) {}
        live.startAutomaticHandling(::checkDeferredDeepLink) { dispatchAutoDeferred(live.autoHandler, it) }
        resumePendingInvitation()
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
        val handler = synchronized(lock) { session?.autoHandler }
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
        val current = synchronized(lock) { session }
        val handler = current?.takeIf { it.options.automaticDeepLinks }?.autoHandler ?: run {
            // Saying nothing here is the failure mode this model exists to
            // avoid. The documented integration is an unconditional one-liner,
            // so a host that turned the flag off in shared config would
            // otherwise watch warm-start links vanish with no explanation.
            current?.logger?.log(
                "onNewIntent ignored: automatic deep link handling is off"
            )
            return
        }
        intent.data?.let { handler.dispatch(it) }
    }

    fun checkDeferredDeepLink(
        callback: (Result<WarpLinkDeepLink?>) -> Unit
    ) {
        val inputs = synchronized(lock) { session }
            ?.deferredCheckInputs(retrySettingsOverride ?: RetrySettings.DEFAULT)
        if (inputs == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }
        deferredChecks.check(inputs, callback)
    }

    /**
     * Redeems an invitation the person pasted.
     *
     * Call this only from a user action, with text the person handed over. The
     * SDK never reads the clipboard on its own. A referral link opened from the
     * Play Store is redeemed without this call.
     *
     * - `success(link)`: the invitation matched. [link] is also cached as
     *   [attributionResult].
     * - `success(null)`: the text was not a WarpLink invitation, or it matched
     *   nothing. Nothing is confirmed, so do not tell the person a referral was
     *   applied.
     * - `failure`: the invitation is invalid or expired (the message says how
     *   to open the link again), or the network failed. After a network failure
     *   the SDK keeps the invitation and tries again on a later launch, a
     *   bounded number of times.
     *
     * Onboarding does not wait on this: skipping it, or any failure, leaves the
     * app fully usable.
     */
    fun redeemInvitation(
        text: String,
        callback: (Result<WarpLinkDeepLink?>) -> Unit
    ) {
        ReferralEntry.redeem(text, referralContext(), callback)
    }

    /**
     * Claims the referral behind this install for the person who just signed up.
     *
     * [installerId] is your own stable id for that person, 1 to 128 characters.
     * Calling again with the same id is safe: the server answers the same claim
     * again rather than crediting twice.
     *
     * [ReferralClaimStatus.NONE][app.warplink.referral.ReferralClaimStatus.NONE]
     * is an ordinary answer, not an error: there was no referral to credit. The
     * call fails only when it cannot reach the server, the key is refused, or
     * [installerId] is empty or too long.
     */
    fun claimReferral(
        installerId: String,
        callback: (Result<ReferralClaimResult>) -> Unit
    ) {
        ReferralEntry.claim(installerId, referralContext(), callback)
    }

    private fun resumePendingInvitation() {
        val handler = synchronized(lock) { session?.autoHandler }
        ReferralEntry.resume(referralContext()) { dispatchAutoDeferred(handler, it) }
    }

    private fun referralContext(): ReferralContext? = synchronized(lock) {
        val current = session ?: return null
        ReferralContext(
            apiClient = current.apiClient,
            storage = current.storage,
            logger = current.logger,
            settings = retrySettingsOverride ?: RetrySettings.DEFAULT,
            onMatch = { deferredChecks.recordMatch(current.storage, it) }
        )
    }

    private fun resolveForCallback(
        uri: Uri,
        callback: (Result<WarpLinkDeepLink>) -> Unit
    ) {
        val current = synchronized(lock) { session }
        if (current == null) {
            callback(Result.failure(WarpLinkError.NotConfigured))
            return
        }
        resolveDeepLink(
            current.apiClient,
            current.logger,
            uri,
            settings = retrySettingsOverride ?: RetrySettings.DEFAULT,
            callback = callback
        )
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
        val current = synchronized(lock) { session }
        if (current?.autoHandler === handler) {
            handler?.dispatchDeferred(result)
        } else {
            current?.logger?.log(
                "Deferred check abandoned; not dispatching to the superseded onLink"
            )
        }
    }

    @VisibleForTesting
    internal fun reset() {
        synchronized(lock) {
            session?.autoHandler?.unregister()
            session = null
            retrySettingsOverride = null
        }
        deferredChecks.reset()
        UriParser.resetKnownDomains()
    }

    private val KEY_REGEX = Regex("^wl_(live|test)_[a-zA-Z0-9]{32}$")
}
