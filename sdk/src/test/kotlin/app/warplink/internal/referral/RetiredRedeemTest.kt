package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.RetrySettings
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.validToken
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A redeem whose answer arrives after its referral state was cleared or orphaned writes nothing. */
class RetiredRedeemTest : ReferralRedeemTestBase() {

    @Test
    fun `an answer that arrives after clearAttribution writes nothing and delivers no match`() {
        assertRetiredRunWritesNothing { storage.clearAttribution() }
    }

    @Test
    fun `an answer that arrives after the redeems are orphaned writes nothing and delivers no match`() {
        assertRetiredRunWritesNothing { storage.referral.orphanRedeems() }
    }

    private fun assertRetiredRunWritesNothing(retire: () -> Unit) {
        val gate = CountDownLatch(1)
        val server = LoopbackJsonServer(TOKEN_MATCH_JSON)
        try {
            server.serveOnce(gate)
            val delivered = mutableListOf<WarpLinkDeepLink>()
            var answer: Result<WarpLinkDeepLink?>? = null
            val finished = CountDownLatch(1)
            val retiring = ReferralContext(
                ApiClient(API_KEY, server.baseUrl), storage, null, RetrySettings.NO_WAIT,
            ) { delivered.add(it); {} }
            ReferralRedeemer.redeem(validToken(), retiring) { answer = it; finished.countDown() }
            assertTrue(server.awaitRequests(1), "the redeem never reached the wire")

            retire()
            val pendingAtRetire = storage.referral.pending.load()
            val evidenceAtRetire = storage.referral.evidence.evidence()
            gate.countDown()
            idleMainLooperUntil(finished)

            assertNull(answer?.getOrThrow(), "a retired run must answer with no match")
            assertTrue(delivered.isEmpty(), "a retired run must not deliver a match")
            assertEquals(pendingAtRetire, storage.referral.pending.load(), "a retired run must not touch the pending record")
            assertEquals(evidenceAtRetire, storage.referral.evidence.evidence(), "a retired run must not write evidence")
        } finally {
            server.close()
        }
    }

    @Test
    fun `a redeem that paused before a clear saves nothing after it and sends nothing`() {
        val server = stub()
        val paused = contextFor(server)

        storage.clearAttribution()
        var answer: Result<WarpLinkDeepLink?>? = null
        ReferralRedeemer.redeem(validToken(), paused) { answer = it }

        assertNull(answer?.getOrThrow())
        assertNull(storage.referral.pending.load(), "a retired call refilled the pending record")
        assertTrue(storage.referral.beginRedeem(), "a retired call kept the redeem claim")
    }

    @Test
    fun `a paste kept for later by a retired call saves nothing after a clear`() {
        val server = stub()
        val paused = contextFor(server)
        assertTrue(storage.referral.beginRedeem())

        storage.clearAttribution()
        ReferralRedeemer.redeem(validToken(), paused) { }

        assertNull(storage.referral.pending.load(), "a retired call refilled the pending record")
    }
}
