package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.ApiClient
import app.warplink.internal.Logger
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage

/**
 * The dependencies of one referral run, gathered once under the SDK lock.
 *
 * @property onMatch runs for an invitation that matched, so the facade can
 * record the exact match and stop the automatic check that may still be on the
 * wire. Its later answer is an estimate or a no-match, and left alone it would
 * route a second, weaker link and overwrite the cache.
 */
internal class ReferralContext(
    val apiClient: ApiClient,
    val storage: Storage,
    val logger: Logger?,
    val settings: RetrySettings,
    val onMatch: (WarpLinkDeepLink) -> Unit,
) {
    val stores: ReferralStores get() = storage.referral
}
