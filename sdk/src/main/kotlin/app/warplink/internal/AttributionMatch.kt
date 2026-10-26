package app.warplink.internal

import app.warplink.WarpLink
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The independent lines of attribution requests, each with its own on-the-wire slot.
 *
 * A pasted invitation and the deferred check run side by side, for different
 * inputs. Sharing one slot made each cancel the other's connection, so a slot
 * belongs to one line and only that line's owner cancels it.
 */
internal enum class MatchSlot {
    /** The deferred check, including the Play referrer token it redeems. */
    DEFERRED_CHECK,

    /** A pasted or resumed invitation. */
    REDEEM,
}

/**
 * The attempt each line currently has on the wire.
 *
 * Superseding a request only stops its NEXT attempt. The one already sent goes
 * on holding its connection for the rest of its readTimeout unless somebody
 * disconnects it, on the network least able to spare it.
 */
private val inFlightMatches = MatchSlot.entries.associateWith { AtomicReference<Cancellable?>(null) }

/**
 * Drop whatever an older request in [slot] left on the wire.
 *
 * Called where a new request supersedes an old one in the same line, for the
 * same reason the resolve path cancels where a new tap supersedes an old one.
 */
internal fun cancelInFlightAttributionMatch(slot: MatchSlot) {
    inFlightMatches.getValue(slot).getAndSet(null)?.cancel()
}

/**
 * One attribution request, retried on a transient failure inside the same
 * bounded budget the resolve path uses.
 *
 * Shared by every caller of the attribution endpoint: the Play Install
 * Referrer branch and the raw-signals branch of the deferred check, and the
 * invitation redeem. They are the same request with a different body, and a
 * retry on only one of them would leave part of first-launch attribution
 * failing on exactly the connection this exists for.
 *
 * `deviceId` is null on every branch, so it is fixed here rather than taken as
 * a parameter nothing varies.
 */
internal fun matchWithRetry(
    apiClient: ApiClient,
    slot: MatchSlot,
    settings: RetrySettings,
    isCurrentCheck: () -> Boolean,
    /** Null for a request that must carry a recovery token and nothing else. */
    signals: DeviceSignals?,
    referrer: String?,
    recoveryToken: String? = null,
    /**
     * Repeated on every attempt. Without a token it is log-only on this
     * endpoint: the server derives the stored click id from the KV payload, not
     * from the client. A token redeem passes the id kept with the token, which
     * the server compares with the install holding it to tell the original from
     * a replay.
     */
    tapId: String = UUID.randomUUID().toString(),
    isReinstall: Boolean,
    deliver: (Result<AttributionResponse>) -> Unit
) {
    val inFlight = inFlightMatches.getValue(slot)
    BoundedRetry(
        scheduler = apiClient,
        settings = settings,
        isCurrent = isCurrentCheck,
    ).run<AttributionResponse>(
        attempt = { number, done ->
            // Recorded so a newer request in this line can disconnect the
            // attempt rather than merely ignore its answer, as the resolve path does.
            inFlight.set(
                apiClient.matchAttribution(
                    signals, WarpLink.SDK_VERSION, null,
                    referrer = referrer,
                    recoveryToken = recoveryToken,
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
