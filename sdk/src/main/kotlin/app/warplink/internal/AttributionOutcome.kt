package app.warplink.internal

import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink

/**
 * Abandoned by a reconfigure or reset while this request was in flight.
 * Nothing is written: the replacement check typically finds the install
 * this one created, so applying this stale no-match would blank the real
 * match the replacement cached. The direct caller is answered with
 * success(null), the same no-op iOS delivers: the SDK is configured, so
 * NotConfigured would be a fabricated error, and the same React Native
 * call must not reject on one platform and resolve on the other. Routing
 * to the host's onLink sink is separately suppressed (see
 * WarpLink.startAutomaticHandling), so an abandoned check stays silent.
 */
internal fun handleIfAbandoned(
    isCurrentCheck: () -> Boolean,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
): Boolean {
    if (isCurrentCheck()) return false
    logger?.log("Deferred check abandoned, discarding its response")
    callback(Result.success(null))
    return true
}

/**
 * The referrer named a link, the server matched it, and the install is
 * recorded. But the Play install began longer ago than the routing window, so
 * this is a long-time user, not a first open: a 1.0.x upgrade, or the first
 * release a customer ships with the SDK. Routing them into months-old content
 * is wrong, and so is caching it for attributionResult to replay, so the check
 * settles with nothing.
 */
internal fun settleWithoutRouting(
    storage: Storage,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink?>) -> Unit
) {
    logger?.log(
        "Attribution recorded for an install that began more than " +
            "${ReferrerRead.ROUTABLE_INSTALL_AGE_SECONDS / 86_400} days ago; not routing a deferred link that old"
    )
    storage.deferredCheckCompleted = true
    storage.cachedAttribution = null
    callback(Result.success(null))
}

/** The response as something the host can actually route, or null if it is not. */
internal fun routableDeepLink(response: AttributionResponse): WarpLinkDeepLink? {
    val linkId = response.linkId
    val destination = response.destinationUrl
    if (!response.matched || linkId == null || destination == null) return null

    val matchType = when (response.matchType?.lowercase()) {
        "deterministic" -> MatchType.DETERMINISTIC
        "probabilistic" -> MatchType.PROBABILISTIC
        else -> null
    }
    return WarpLinkDeepLink(
        linkId = linkId,
        destination = destination,
        deepLinkUrl = response.deepLinkUrl,
        customParams = response.customParams ?: emptyMap(),
        isDeferred = true,
        matchType = matchType,
        matchConfidence = response.matchConfidence,
        matchGuaranteed = response.matchGuaranteed
    )
}
