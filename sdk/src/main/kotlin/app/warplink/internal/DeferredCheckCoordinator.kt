package app.warplink.internal

import app.warplink.WarpLinkDeepLink

/** What a deferred check reads, captured together so they come from one `configure`. */
internal class DeferredCheckInputs(
    val storage: Storage,
    val fingerprintCollector: FingerprintCollector,
    val apiClient: ApiClient,
    val installReferrerReader: InstallReferrerReader,
    val logger: Logger?,
    val settings: RetrySettings,
    /** Wall-clock milliseconds, for the traffic rules. Overridden only by tests. */
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * Runs the deferred check at most once at a time, and answers every caller that
 * asked while it ran with its result.
 *
 * Callbacks that arrive while a check is already running are drained with that
 * check's own result, so a manual caller never loses a first-launch match to
 * the automatic check that ran before it.
 *
 * The generation is bumped whenever an in-flight check is abandoned (reconfigure,
 * reset, an exact match recorded elsewhere). A check captures the value at start
 * and compares it on completion, so a stale response answers only its own caller
 * instead of clearing the replacement's in-flight flag or draining its parked
 * callbacks, and applies nothing to storage (see `performDeferredCheck`'s
 * `isCurrentCheck`).
 *
 * Before it sends anything it applies the traffic rules (see [decideTraffic]).
 * A check they hold back answers success(null) at once and sends nothing, and
 * leaves the completion gate open. Every check logs its outcome once.
 *
 * Its own lock guards the four fields. It is taken inside `storage.stateLock`
 * (see [Storage.stateLock]), never the other way round, and never held across a
 * callback.
 */
internal class DeferredCheckCoordinator {

    private val lock = Any()
    private var inFlight = false
    private var generation = 0
    private var currentRun: AttemptRun? = null
    private val parked = mutableListOf<(Result<WarpLinkDeepLink?>) -> Unit>()

    /** Whether a check is on the wire. */
    val isRunning: Boolean get() = synchronized(lock) { inFlight }

    fun check(inputs: DeferredCheckInputs, callback: (Result<WarpLinkDeepLink?>) -> Unit) {
        val storage = inputs.storage
        if (storage.deferredCheckCompleted) {
            logOutcome(inputs)
            callback(Result.success(storage.cachedAttribution))
            return
        }
        val started = begin(callback)
        if (started == null) {
            inputs.logger?.log("Deferred check already in flight, awaiting its result")
            return
        }
        val complete = { result: Result<WarpLinkDeepLink?> ->
            val waiting = finish(started)
            if (waiting != null) logOutcome(inputs)
            callback(result)
            waiting.orEmpty().forEach { it(result) }
        }
        val decision = storage.traffic.decide(inputs.clock())
        if (decision !is TrafficDecision.Proceed) {
            if (decision == TrafficDecision.SkipInstallAge) storage.traffic.noteSkippedForAge()
            complete(Result.success(null))
            return
        }
        val run = AttemptRun(storage.traffic, inputs.clock, inputs.logger, decision.sendRawSignals)
        synchronized(lock) { if (started == generation) currentRun = run }
        performDeferredCheck(
            storage,
            inputs.fingerprintCollector,
            inputs.apiClient,
            inputs.installReferrerReader,
            inputs.logger,
            isCurrentCheck = { isCurrent(started) },
            settings = inputs.settings,
            sendRawSignals = decision.sendRawSignals,
            attempt = run,
            callback = complete
        )
    }

    /** One line per check: where it left the install, which also says why a held back check sent nothing. */
    private fun logOutcome(inputs: DeferredCheckInputs) {
        val logger = inputs.logger ?: return
        val status = deferredCheckStatusOf(inputs.storage, isRunning, inputs.clock())
        logger.log("Deferred check: ${describeStatus(status)}")
    }

    /**
     * Abandons the check in flight, if any, and answers the callers parked on it
     * with success(null). Called by a reconfigure: leaving them queued would let
     * the abandoned check deliver its result to callers that parked on a later one.
     */
    fun abandon() {
        abandonStaged()()
    }

    /**
     * Abandons the check in flight now and returns the step that answers its
     * parked callers with success(null). The caller runs that step after it
     * has released its own locks, so no host callback runs under them.
     */
    fun abandonStaged(): () -> Unit {
        val waiting = release()
        return { waiting.forEach { it(Result.success(null)) } }
    }

    /** Abandons the check in flight and drops its parked callers unanswered. */
    fun reset() {
        release()
    }

    /**
     * Records the exact match an invitation redeemed, and stops the automatic
     * deferred check, which may still be on the wire. That check's later answer
     * is an estimate or a no-match; left alone it would route a second, weaker
     * link and overwrite the cache. A caller parked on it gets the exact match.
     */
    fun recordMatch(storage: Storage, link: WarpLinkDeepLink) {
        synchronized(storage.stateLock) { stageMatch(storage, link) }()
    }

    /**
     * Stores [link] and abandons the check in flight, and returns the step that
     * cancels its request and answers its parked callers. The caller holds
     * `storage.stateLock`, so a check that asks `isCurrent` sees either the world
     * before the match or the world after it, never a stored match with the
     * check still current. It runs the returned step after releasing its locks.
     */
    fun stageMatch(storage: Storage, link: WarpLinkDeepLink): () -> Unit {
        storage.deferredCheckCompleted = true
        storage.cachedAttribution = link
        val waiting = release()
        return {
            cancelInFlightAttributionMatch(MatchSlot.DEFERRED_CHECK)
            waiting.forEach { it(Result.success(link)) }
        }
    }

    /** The generation this check runs under, or null after parking [callback] on the running one. */
    private fun begin(callback: (Result<WarpLinkDeepLink?>) -> Unit): Int? = synchronized(lock) {
        if (inFlight) {
            parked.add(callback)
            null
        } else {
            inFlight = true
            generation
        }
    }

    private fun isCurrent(started: Int): Boolean = synchronized(lock) { started == generation }

    /**
     * The callers parked on check [started], or null when it was abandoned: its
     * parked callers were answered then, and touching the shared state now would
     * release the replacement check and steal its callbacks. An abandoned check
     * also logs no outcome, since its storage and logger are no longer the SDK's.
     */
    private fun finish(started: Int): List<(Result<WarpLinkDeepLink?>) -> Unit>? = synchronized(lock) {
        if (started != generation) return@synchronized null
        inFlight = false
        currentRun = null
        drain()
    }

    private fun release(): List<(Result<WarpLinkDeepLink?>) -> Unit> = synchronized(lock) {
        generation++
        inFlight = false
        currentRun?.abandon()
        currentRun = null
        drain()
    }

    private fun drain(): List<(Result<WarpLinkDeepLink?>) -> Unit> =
        parked.toList().also { parked.clear() }
}
