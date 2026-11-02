package app.warplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import app.warplink.internal.RetrySettings
import app.warplink.internal.UriParser
import app.warplink.internal.WarpLinkRuntime
import app.warplink.internal.referral.ReferralEntry

object WarpLink {

    /**
     * The SDK version. Sourced from the build's VERSION_NAME (the published
     * Maven coordinate) via [BuildConfig] — do not replace this with a hardcoded
     * literal. Keeping it generated guarantees the User-Agent and attribution
     * `sdk_version` always match the released version, with nothing to bump by
     * hand at release time beyond the git tag.
     */
    val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private val runtime = WarpLinkRuntime()

    /**
     * Retry settings for the funnels this facade drives, overridden only by tests.
     * Cleared by [reset] so one test cannot leak it into the next.
     */
    @VisibleForTesting
    internal var retrySettingsOverride: RetrySettings?
        get() = runtime.retrySettingsOverride
        set(value) { runtime.retrySettingsOverride = value }

    val isConfigured: Boolean
        get() = runtime.isConfigured

    /** Cached attribution result from the last deferred deep link check, or null if none. */
    val attributionResult: WarpLinkDeepLink?
        get() = runtime.attributionResult

    /**
     * Whether the deferred attribution check has definitively completed for this
     * install. The React Native bridge reads this so a deferred match reaches
     * `onLink` at most once across launches, matching the native auto-dispatch guard.
     */
    fun isAttributionComplete(): Boolean = runtime.isAttributionComplete

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
    ) = runtime.configure(context, apiKey, options)

    /**
     * Forgets this install's attribution so the next first launch can be tested
     * again: the cached match, the completed and attempted gates, the device-seen
     * flag and the referral records. A check in flight is abandoned and its late
     * answer writes nothing. The key validation and link domains are kept.
     *
     * Test only. With a `wl_live_` key this does nothing and succeeds, so it can
     * never erase a live install's attribution. With automatic deferred deep
     * links on, a fresh check starts.
     *
     * - `success`: the state is cleared, or a live key made this a no-op.
     * - `failure`: [WarpLinkError.NotConfigured] before [configure], or
     *   [WarpLinkError.DecodingError] when a file or preference could not be
     *   deleted and some state may remain.
     */
    fun clearAttribution(): Result<Unit> = runtime.clearAttribution()

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
    ) = runtime.handleDeepLink(uri, callback)

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
    fun onNewIntent(intent: Intent) = runtime.onNewIntent(intent.data)

    fun checkDeferredDeepLink(
        callback: (Result<WarpLinkDeepLink?>) -> Unit
    ) = runtime.checkDeferred(callback)

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
        ReferralEntry.redeem(text, runtime.referralContext(), callback)
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
        ReferralEntry.claim(installerId, runtime.referralContext(), callback)
    }

    @VisibleForTesting
    internal fun reset() = runtime.reset()
}
