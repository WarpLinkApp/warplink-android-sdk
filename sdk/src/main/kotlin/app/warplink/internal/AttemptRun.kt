package app.warplink.internal

/**
 * One deferred check run's claim on an attempt number.
 *
 * The attempt counts when the run sends its first request and not before, and
 * once for the run however many requests it sends (a token redeem and then the
 * link id, or a retry inside the launch). A run that is abandoned gives its
 * attempt back, so the check that replaces it is not held up by it.
 */
internal class AttemptRun(
    private val traffic: DeferredTraffic,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: Logger? = null,
    /** What the check decided: whether the requests of this run may carry raw device signals. */
    private val sendRawSignals: Boolean = true,
) {

    private var grant: AttemptGrant? = null
    private var abandoned = false
    private var requestLogged = false

    /** The attempt number to send. Counts the attempt on first use. */
    val number: Int
        get() = synchronized(this) {
            when {
                abandoned -> grant?.number ?: traffic.peekNextAttemptNumber()
                else -> (grant ?: traffic.beginAttempt(clock()).also { grant = it }).number
            }
        }

    /**
     * Logs the run's attempt number and whether the check lets its requests
     * carry raw device signals. It states the check's decision, so a token
     * redeem that goes first without them does not hide a later request that
     * sends them. Once per run: a retry inside the launch, or the second
     * request of a token redeem and a link id, adds no line.
     */
    fun logRequest() {
        val first = synchronized(this) { !requestLogged.also { requestLogged = true } }
        if (first) {
            logger?.log("Deferred check request: attempt $number, raw signals ${if (sendRawSignals) "yes" else "no"}")
        }
    }

    /** Logs what a failed request ran into, and whether the same launch tries again. */
    fun logFailedTry(error: Throwable, tryNumber: Int, willRetry: Boolean, settings: RetrySettings) {
        logger?.log(describeFailedTry(error, tryNumber, willRetry, settings))
    }

    /** The run was superseded: gives its attempt back and counts nothing from now on. */
    fun abandon() = synchronized(this) {
        abandoned = true
        grant?.let(traffic::undoAttempt)
    }
}
