package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.ApiClient
import app.warplink.internal.Logger
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage

/**
 * The dependencies of one referral run, gathered once under the SDK lock.
 *
 * @property onMatch runs for an invitation that matched, under
 * `storage.stateLock`. It records the exact match and stops the automatic check
 * that may still be on the wire, since that check's later answer is an
 * estimate or a no-match that would route a second, weaker link and overwrite
 * the cache. It returns the step that tells the callers waiting on that check,
 * which the redeemer runs after it releases the lock.
 * @property epoch the referral epoch when this context was built. The run
 * writes only while the store is still on it.
 */
internal class ReferralContext(
    val apiClient: ApiClient,
    val storage: Storage,
    val logger: Logger?,
    val settings: RetrySettings,
    val onMatch: (WarpLinkDeepLink) -> () -> Unit,
) {
    val stores: ReferralStores get() = storage.referral

    val epoch: Int = storage.referral.epoch

    /** Whether a clear or a reconfigure has retired the redeems of this context. */
    val isRetired: Boolean get() = stores.epoch != epoch
}
