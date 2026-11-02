package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Answer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.RetrySettings
import app.warplink.internal.performDeferredCheck
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.validToken
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An invitation redeem and the deferred check run side by side for different
 * inputs. Each owns its own attempt on the wire: starting one must not
 * disconnect the other.
 */
class ConcurrentMatchTest : ReferralRedeemTestBase() {

    private val servers = mutableListOf<LoopbackJsonServer>()

    @After
    fun closeServers() {
        servers.forEach { it.close() }
    }

    @Test
    fun `WL-S44 a deferred check does not disconnect a redeem that is on the wire`() {
        val redeemServer = server().also { it.serveScript(Answer.HANG, Answer.HANG) }
        var redeemAnswer: Result<WarpLinkDeepLink?>? = null
        val redeemContext = ReferralContext(
            ApiClient(API_KEY, redeemServer.baseUrl), storage, null, HELD,
        ) { {} }
        ReferralRedeemer.redeem(validToken(), redeemContext) { redeemAnswer = it }
        assertTrue(redeemServer.awaitRequests(1), "the redeem never reached the wire")

        runDeferredCheck(server(NO_MATCH_JSON).also { it.serveOnce() })

        // A disconnect reads as a network failure, which the redeem retries.
        assertFalse(redeemServer.awaitRequests(2, seconds = 1), "the redeem was disconnected and retried")
        assertNull(redeemAnswer)
    }

    private fun server(body: String = "{}") = LoopbackJsonServer(body).also { servers.add(it) }

    private fun runDeferredCheck(server: LoopbackJsonServer) {
        val done = CountDownLatch(1)
        storage.deferredCheckCompleted = false
        performDeferredCheck(
            storage, FingerprintCollector(), ApiClient(API_KEY, server.baseUrl),
            null, null, isCurrentCheck = { true }, settings = RetrySettings.NO_WAIT,
        ) { done.countDown() }
        idleMainLooperUntil(done)
    }

    private companion object {
        const val NO_MATCH_JSON = """{"matched":false}"""

        /** Long enough that only a disconnect can end an attempt early. */
        val HELD = RetrySettings(
            delaysMs = listOf(0L),
            maxJitterMs = 0L,
            totalBudgetMs = Long.MAX_VALUE,
            firstAttemptTimeoutMs = 8_000,
            retryTimeoutMs = 8_000,
        )
    }
}
