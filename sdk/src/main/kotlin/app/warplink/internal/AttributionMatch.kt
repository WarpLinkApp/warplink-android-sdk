package app.warplink.internal

import app.warplink.WarpLink
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The attribution attempt the deferred check currently has on the wire.
 *
 * Superseding a check only stops its NEXT attempt. The one already sent goes on
 * holding its connection for the rest of its readTimeout unless somebody
 * disconnects it, on the network least able to spare it.
 */
private val inFlightMatch = AtomicReference<Cancellable?>(null)

/**
 * Drop whatever an older deferred check left on the wire.
 *
 * Called where a new check supersedes an old one, for the same reason the
 * resolve path cancels where a new tap supersedes an old one.
 */
internal fun cancelInFlightAttributionMatch() {
    inFlightMatch.getAndSet(null)?.cancel()
}

/**
 * One attribution request, retried on a transient failure inside the same
 * bounded budget the resolve path uses.
 *
 * Shared by BOTH of the deferred check's branches. The Play Install Referrer
 * branch and the raw-signals branch are the same request with a different body,
 * and a retry on only one of them would leave half of first-launch attribution
 * failing on exactly the connection this exists for.
 *
 * Extracted out of DeferredDeepLink.kt, which orchestrates the check (its gate,
 * its cache, its referrer fallback) and was already close to the file size cap.
 * Asking the server is a separate job, and it is the one the retry wraps.
 *
 * `deviceId` is null on both branches today, so it is fixed here rather than
 * taken as a parameter nothing varies.
 */
internal fun matchWithRetry(
    apiClient: ApiClient,
    settings: RetrySettings,
    isCurrentCheck: () -> Boolean,
    signals: DeviceSignals,
    referrer: String?,
    isReinstall: Boolean,
    deliver: (Result<AttributionResponse>) -> Unit
) {
    // One id for this check, repeated on every attempt. LOG-ONLY on this
    // endpoint: `app_installs.click_id` is uniquely indexed, but the server
    // derives its value from the KV deferred payload rather than from the
    // client, so this header dedupes nothing here. It is sent so one check is
    // traceable across its own retries.
    val tapId = UUID.randomUUID().toString()

    BoundedRetry(
        scheduler = apiClient,
        settings = settings,
        isCurrent = isCurrentCheck,
    ).run<AttributionResponse>(
        attempt = { number, done ->
            // Recorded so a newer check can disconnect this attempt rather than
            // merely ignore its answer, exactly as the resolve path does.
            inFlightMatch.set(
                apiClient.matchAttribution(
                    signals, WarpLink.SDK_VERSION, null,
                    referrer = referrer,
                    isReinstall = isReinstall,
                    tapId = tapId,
                    // Every attempt is bounded, the first included. First launch
                    // on a slow connection is the case this feature serves,
                    // which is why attempt 1 gets the LONGER bound rather than
                    // no bound: an unbounded one hangs for READ_TIMEOUT_MS and
                    // spends the whole budget before any retry runs.
                    timeoutMs = settings.timeoutMsFor(number),
                    callback = done,
                )
            )
        },
        // A run superseded while it waits resumes on the executor, so without
        // this hop its answer would reach the host off the main thread.
        deliver = { result -> onMainThread { deliver(result) } },
    )
}
