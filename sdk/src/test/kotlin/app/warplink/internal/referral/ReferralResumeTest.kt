package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer.Reply
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.expiredToken
import app.warplink.internal.referral.ReferralFixtures.validToken
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a redeem keeps for a retry: the bounded, install-scoped record, and how
 * a later launch finishes it.
 */
class ReferralResumeTest : ReferralRedeemTestBase() {

    @Test
    fun `WL-S44 the attempt is counted before the request goes out and persists after a failure`() {
        val server = stub(status = 500)
        server.serveReplies(Reply(500, "{}"), Reply(500, "{}"), Reply(500, "{}"))

        redeem(server, validToken())

        assertEquals(1, storage.referral.pending.load()?.attempts)
    }

    @Test
    fun `WL-S44 a failed paste is kept in install scoped storage and finished on a later launch`() {
        val first = stub()
        first.serveReplies(Reply(500, "{}"), Reply(500, "{}"), Reply(500, "{}"))
        val token = validToken()
        redeem(first, token)
        assertTrue(
            context.noBackupFilesDir.resolve("warplink_referral_pending.json").exists(),
            "the record must live where a restore does not copy it",
        )

        val second = stub()
        second.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        val resumed = resume(second)

        assertEquals(LINK_ID, resumed.getOrThrow()?.linkId)
        assertEquals(1, second.requestLines.size)
        assertTrue(second.requestBodies.single().contains(token))
        assertNull(storage.referral.pending.load(), "one token gives one delivery")
    }

    @Test
    fun `WL-S44 one token gives one delivery`() {
        val server = stub()
        server.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        redeem(server, validToken())

        val again = stub()
        resumeQuietly(again)

        assertEquals(1, matches.size)
        assertEquals(0, again.requestLines.size)
    }

    @Test
    fun `WL-S44 a paste while a redeem is running is kept and sends nothing`() {
        val server = stub()
        assertTrue(storage.referral.beginRedeem())
        val token = validToken()

        val result = redeem(server, token)

        assertNull(result.getOrThrow())
        assertEquals(0, server.requestLines.size)
        assertEquals(token, storage.referral.pending.load()?.token)
    }

    @Test
    fun `WL-S44 the attempts are bounded and a spent record is dropped`() {
        val token = validToken()
        val last = PendingRedemption.MAX_ATTEMPTS - 1
        storage.referral.pending.save(PendingRedemption(token, expiryOf(token), attempts = last))
        val server = stub()
        server.serveReplies(Reply(500, "{}"), Reply(500, "{}"), Reply(500, "{}"))

        resume(server)

        assertNull(storage.referral.pending.load())
        val spent = stub()
        resumeQuietly(spent)
        assertEquals(0, spent.requestLines.size)
    }

    @Test
    fun `WL-S44 a record already out of attempts sends nothing`() {
        val token = validToken()
        storage.referral.pending.save(
            PendingRedemption(token, expiryOf(token), PendingRedemption.MAX_ATTEMPTS)
        )
        val server = stub()

        resumeQuietly(server)

        assertEquals(0, server.requestLines.size)
        assertNull(storage.referral.pending.load())
    }

    @Test
    fun `WL-S44 a record past its own expiry is dropped without a request`() {
        val token = expiredToken()
        storage.referral.pending.save(PendingRedemption(token, expiryOf(token), 1))
        val server = stub()

        resumeQuietly(server)

        assertEquals(0, server.requestLines.size)
        assertNull(storage.referral.pending.load())
    }

    @Test
    fun `WL-S44 clearing is atomic against a newer paste`() {
        val older = validToken(filler = 1)
        val newer = validToken(filler = 2)
        val pending = storage.referral.pending
        pending.save(PendingRedemption(older, expiryOf(older), 1))

        pending.save(PendingRedemption(newer, expiryOf(newer), 0))
        pending.clearIfHolding(older)

        assertEquals(newer, pending.load()?.token, "the older run must not clear the newer paste")
        pending.clearIfHolding(newer)
        assertNull(pending.load())
    }

    @Test
    fun `WL-S44 wiping the SDK state drops the record`() {
        val token = validToken()
        storage.referral.pending.save(PendingRedemption(token, expiryOf(token), 1))

        storage.clearAll()

        assertNull(storage.referral.pending.load())
    }
}
