package app.warplink.internal

import android.content.Context
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.referral.ReferralFixtures
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNotNull

internal const val LIVE_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
internal const val TEST_KEY = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
internal const val NO_MATCH = """{"matched":false}"""
internal const val LINK_MATCH =
    """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"lnk_early","destination_url":"https://example.com/promo"}"""

/** A Play install referrer from a WarpLink link, the evidence an install past the raw signal age still sends. */
internal const val LINK_REFERRER = "utm_source=warplink&utm_content=${ReferralFixtures.LINK_ID}"

private const val DAY_MS = 86_400_000L

/**
 * A device that launches the app again and again. Each [launch] is a fresh
 * coordinator and a fresh [Storage] over the same files, as a new process is,
 * and the clock only moves when the test says so.
 */
internal class TrafficDevice(
    private val context: Context,
    private val key: String = LIVE_KEY,
) {
    var nowMs = 1_800_000_000_000L

    /** Where the check logs. Null keeps a test quiet. */
    var logger: Logger? = null
    private val servers = mutableListOf<LoopbackJsonServer>()

    init {
        Storage(context, KeyEnvironment.of(key)).clearAll()
        installedDaysAgo(0)
    }

    /** Storage reads the install time once, so set it before the next launch. */
    fun installedDaysAgo(days: Long, hours: Long = 0) {
        val info = Shadows.shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = nowMs - days * DAY_MS - hours * 3_600_000L
    }

    fun advanceMs(ms: Long) {
        nowMs += ms
    }

    fun storage() = Storage(context, KeyEnvironment.of(key))

    /** A server that answers 500 to the three requests of one run. */
    fun failing(): LoopbackJsonServer = server(NO_MATCH, 500).also { it.serveSequence(true, true, true) }

    /** A server that answers [body] to one request. */
    fun answering(body: String = NO_MATCH): LoopbackJsonServer = server(body).also { it.serveOnce() }

    /** A server that answers no match to the two requests of a token redeem and the link id request after it. */
    fun answeringTwice(): LoopbackJsonServer = server(NO_MATCH).also { it.serveSequence(true, true) }

    /** A server that must receive nothing. */
    fun silent(): LoopbackJsonServer = server(NO_MATCH)

    private fun server(body: String, status: Int = 200) =
        LoopbackJsonServer(body, status = status).also { servers.add(it) }

    /** The Play Install Referrer says [referrer], or there is no Play Store when it is null. */
    private fun referrerReader(referrer: String?) = InstallReferrerReader(context) {
        if (referrer == null) error("no Play Store in a unit test")
        FakeInstallReferrerClient.respondWith(InstallReferrerResponseCodes.OK, referrer)
    }

    fun inputs(storage: Storage, server: LoopbackJsonServer, referrer: String? = null) = DeferredCheckInputs(
        storage = storage,
        fingerprintCollector = FingerprintCollector(),
        apiClient = ApiClient(key, server.baseUrl),
        installReferrerReader = referrerReader(referrer),
        logger = logger,
        settings = RetrySettings.NO_WAIT,
        clock = { nowMs },
    )

    /** One launch: a new process runs one deferred check against [server] and returns its answer. */
    fun launch(
        server: LoopbackJsonServer,
        storage: Storage = storage(),
        referrer: String? = null,
    ): Result<WarpLinkDeepLink?> {
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        DeferredCheckCoordinator().check(inputs(storage, server, referrer)) {
            result = it
            done.countDown()
        }
        idleMainLooperUntil(done)
        return assertNotNull(result)
    }

    fun close() = servers.forEach { it.close() }
}

/** Polls [condition] on real time without idling the main looper, so a hand-off to it cannot run. */
internal fun pollUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(2)
    }
    if (!condition()) throw AssertionError("timed out after ${timeoutMs}ms: $message")
}
