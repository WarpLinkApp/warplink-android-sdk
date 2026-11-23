package app.warplink.internal.referral

import app.warplink.internal.ApiClient
import app.warplink.internal.AttemptRun
import app.warplink.internal.MatchSlot
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.matchWithRetry

/**
 * Sends the Tether test code the Play referrer carried and keeps the receipt.
 *
 * The request carries the code and nothing else, as for a live code. The server
 * records nothing for a test code, so the SDK stores nothing either: no install,
 * no referral evidence and no tap id. The one thing kept is the receipt, in
 * memory, for `lastTetherTestReceipt`.
 *
 * It is one attempt and the answer is always [ReferrerTokenOutcome.FallThrough].
 * A refused, unknown or lost code must not hold up the install referrer link id,
 * the device id or the device signals behind it.
 */
internal fun redeemTetherTestCode(
    code: String,
    apiClient: ApiClient,
    storage: Storage,
    settings: RetrySettings,
    isCurrentCheck: () -> Boolean,
    attempt: AttemptRun,
    deliver: (ReferrerTokenOutcome) -> Unit,
) {
    matchWithRetry(
        apiClient, MatchSlot.DEFERRED_CHECK, settings.copy(maxAttempts = 1), isCurrentCheck,
        storage.traffic, attempt,
        signals = null,
        referrer = null,
        tetherCode = code,
        isReinstall = storage.isReinstall,
    ) { result ->
        synchronized(storage.stateLock) {
            val tether = result.getOrNull()?.tether
            if (isCurrentCheck() && tether?.test == true) {
                storage.referral.tetherTestReceipt = tether.testReceipt
            }
        }
        deliver(ReferrerTokenOutcome.FallThrough)
    }
}
