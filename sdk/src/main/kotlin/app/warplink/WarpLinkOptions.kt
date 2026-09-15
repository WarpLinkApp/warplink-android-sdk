package app.warplink

/**
 * Configuration for [WarpLink.configure].
 *
 * The SDK is **opt-out**: with a bare `configure(context, apiKey)` the SDK
 * automatically handles cold-start deep links (via `ActivityLifecycleCallbacks`)
 * and fires the deferred deep link check on first launch. Provide [onLink] to
 * receive those results in one place. Set [automaticDeepLinks] or
 * [automaticDeferredDeepLinks] to `false` to opt a piece back out and drive it
 * yourself with [WarpLink.handleDeepLink] / [WarpLink.checkDeferredDeepLink].
 *
 * @property apiEndpoint API base URL. Override for testing or custom deployments.
 * @property debugLogging Enable `WarpLink`-tagged Logcat output.
 * @property automaticDeepLinks When `true` (default) the SDK registers
 *   `ActivityLifecycleCallbacks` and resolves cold-start deep links from the
 *   launching activity's intent, dispatching results to [onLink]. Warm-start
 *   (an already-running task receiving a new intent) still needs the host
 *   one-liner [WarpLink.onNewIntent], which this flag also gates: set it to
 *   `false` and both cold and warm start become yours to route via
 *   [WarpLink.handleDeepLink].
 * @property automaticDeferredDeepLinks When `true` (default) `configure()`
 *   auto-fires the first-launch deferred check and dispatches a match to [onLink].
 * @property linkDomains Your verified custom link domains, for example
 *   `listOf("links.yourapp.com")`. Optional and additive: `aplnk.to` and the
 *   domains your organization has verified are recognized without it, once
 *   `/sdk/validate` has answered. Declaring them here makes them recognized
 *   from the first line of `configure()`, which is what a link opened on a
 *   genuinely first launch (or any launch with no network) needs, since the
 *   SDK must answer "is this URI mine" before a server response can exist.
 *   Full URLs are accepted and reduced to their host. The equivalent no-code
 *   declaration is a manifest `<meta-data android:name="app.warplink.DOMAINS"
 *   android:value="links.yourapp.com,links.other.com" />`; both are unioned.
 * @property onLink Single sink for every resolved link: cold start, warm start,
 *   and deferred matches (disambiguate deferred via `WarpLinkDeepLink.isDeferred`).
 *   A deferred "no match" is not dispatched. Failures are delivered as
 *   `Result.failure`.
 */
data class WarpLinkOptions(
    val apiEndpoint: String = "https://api.warplink.app/v1",
    val debugLogging: Boolean = false,
    val automaticDeepLinks: Boolean = true,
    val automaticDeferredDeepLinks: Boolean = true,
    val linkDomains: List<String> = emptyList(),
    val onLink: ((Result<WarpLinkDeepLink>) -> Unit)? = null
)
