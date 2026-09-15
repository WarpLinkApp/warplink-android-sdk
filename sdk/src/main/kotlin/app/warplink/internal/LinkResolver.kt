package app.warplink.internal

import android.net.Uri
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bumped by every resolve. An attempt captures the value and compares it before
 * each retry, so a newer tap stops the older one's retries. Only monotonic, so
 * it needs no reset. Same primitive as WarpLink.deferredCheckGeneration,
 * applied to taps.
 */
private val tapGeneration = AtomicInteger(0)

/**
 * The attempt a tap currently has on the wire, if any.
 *
 * Bumping the generation only stops the NEXT attempt. The one already sent goes
 * on holding its connection for the rest of its readTimeout unless somebody
 * disconnects it.
 */
private val inFlightTap = AtomicReference<Cancellable?>(null)

/**
 * Resolves a WarpLink App Link URI to a [WarpLinkDeepLink] via the resolve API.
 * Shared by the public [app.warplink.WarpLink.handleDeepLink] and the automatic
 * cold/warm-start dispatch so both paths behave identically. Foreign URIs fail
 * fast locally (no network) with [WarpLinkError.InvalidUrl].
 *
 * A transient failure is retried inside a bounded budget: connection refused,
 * DNS failure, timeout, a connection dropped mid-response, and 5xx. Every 4xx is
 * a refusal answered on the first attempt.
 *
 * @param settings overridden only by tests, so a retry test asserts on what was
 * sent rather than racing Robolectric's shadow clock.
 */
internal fun resolveDeepLink(
    apiClient: ApiClient,
    logger: Logger?,
    uri: Uri,
    settings: RetrySettings = RetrySettings.DEFAULT,
    callback: (Result<WarpLinkDeepLink>) -> Unit
) {
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
    logger?.log("Resolving deep link: $slug@$domain")

    val generation = tapGeneration.incrementAndGet()
    // A newer tap stops the older one's request. Ignoring the older attempt is
    // not enough: its connection would stay open for the rest of its timeout.
    inFlightTap.getAndSet(null)?.cancel()

    retryResolve(apiClient, slug, domain, uri.encodedQuery, settings, generation) { result ->
        onMainThread { deliver(result, logger, callback) }
    }
}

/**
 * Ask the server for [slug] up to [settings].maxAttempts times.
 *
 * Extracted rather than inlined, exactly as iOS extracted its own: the funnel
 * was already close to the fifty line cap before the retry existed.
 */
private fun retryResolve(
    apiClient: ApiClient,
    slug: String,
    domain: String,
    // uri.encodedQuery is the raw query string, encoded once on the wire, so
    // the server's single decode hands its query parser the same bytes a
    // browser sends.
    params: String?,
    settings: RetrySettings,
    generation: Int,
    deliver: (Result<LinkResponse>) -> Unit
) {
    // One id for this tap, repeated on every attempt. Without it a retried
    // attempt whose response was lost would bill a second click for one tap.
    val tapId = UUID.randomUUID().toString()

    BoundedRetry(
        scheduler = apiClient,
        settings = settings,
        isCurrent = { tapGeneration.get() == generation },
    ).run(
        attempt = { number, done ->
            val task = apiClient.resolveLink(
                slug, domain, params, tapId,
                // EVERY attempt is bounded, the first included. ApiClient's own
                // CONNECT_TIMEOUT_MS is 15s and READ_TIMEOUT_MS is 30s, and on
                // the network this exists for the first attempt hangs rather
                // than failing, so an unbounded attempt 1 would spend the whole
                // budget and no retry would ever run.
                settings.timeoutMsFor(number),
                done,
            )
            // Recorded so a newer tap can disconnect this attempt rather than
            // merely ignore its answer.
            if (tapGeneration.get() == generation) inFlightTap.set(task) else task.cancel()
        },
        deliver = deliver,
    )
}

private fun deliver(
    result: Result<LinkResponse>,
    logger: Logger?,
    callback: (Result<WarpLinkDeepLink>) -> Unit
) {
    result.onSuccess { response ->
        logger?.log("Deep link resolved: ${response.id}")
        callback(
            Result.success(
                WarpLinkDeepLink(
                    linkId = response.id,
                    destination = response.destinationUrl,
                    deepLinkUrl = response.androidUrl,
                    customParams = response.customParams,
                    isDeferred = false,
                    matchType = MatchType.DETERMINISTIC,
                    matchConfidence = 1.0,
                    matchGuaranteed = true
                )
            )
        )
    }
    result.onFailure { error ->
        logger?.log("Deep link resolution failed: ${error.message}")
        callback(Result.failure(error))
    }
}
