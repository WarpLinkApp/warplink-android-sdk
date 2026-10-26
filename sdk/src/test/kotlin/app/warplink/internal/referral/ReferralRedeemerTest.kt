package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer.Reply
import app.warplink.WarpLinkError
import app.warplink.internal.referral.ReferralFixtures.EXPIRED_BODY
import app.warplink.internal.referral.ReferralFixtures.INVALID_BODY
import app.warplink.internal.referral.ReferralFixtures.PENDING_BODY
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.expiredToken
import app.warplink.internal.referral.ReferralFixtures.validToken
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Redeeming a pasted invitation: what is sent and what the person is told when
 * the invitation cannot be used.
 */
class ReferralRedeemerTest : ReferralRedeemTestBase() {

    @Test
    fun `WL-S41 text that is not an invitation sends nothing and records nothing`() {
        val server = stub()

        val result = redeem(server, "my verification code is 123456")

        assertNull(result.getOrThrow())
        assertEquals(0, server.requestLines.size)
        assertNull(storage.referral.pending.load())
        assertEquals(ReferralEvidence(), storage.referral.evidence.evidence())
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `WL-S41 an empty paste is not an error and sends nothing`() {
        val server = stub()

        assertNull(redeem(server, "").getOrThrow())
        assertEquals(0, server.requestLines.size)
    }

    @Test
    fun `WL-S42 an expired invitation is refused on the device with no request`() {
        val server = stub()

        val error = redeem(server, expiredToken()).exceptionOrNull()

        val serverError = assertIs<WarpLinkError.ServerError>(error)
        assertEquals(400, serverError.statusCode)
        assertEquals(ReferralMessages.EXPIRED_INVITATION, serverError.message)
        assertEquals(0, server.requestLines.size)
        assertNull(storage.referral.pending.load())
    }

    @Test
    fun `WL-S42 an invalid answer is a failure that says to reopen the invitation, not retried`() {
        val server = stub()
        server.serveReplies(Reply(400, INVALID_BODY))
        val token = validToken()

        val error = assertIs<WarpLinkError.ServerError>(redeem(server, token).exceptionOrNull())

        assertEquals(ReferralMessages.INVALID_INVITATION, error.message)
        assertTrue(error.message!!.contains("Open the invitation link again"))
        assertEquals(1, server.requestLines.size, "a refusal must not be retried")
        assertNull(storage.referral.pending.load(), "a refusal clears the kept invitation")
    }

    @Test
    fun `WL-S42 an expired answer does not claim the invitation expired`() {
        val server = stub()
        server.serveReplies(Reply(400, EXPIRED_BODY))

        val error = assertIs<WarpLinkError.ServerError>(redeem(server, validToken()).exceptionOrNull())

        assertEquals(ReferralMessages.UNUSABLE_INVITATION, error.message)
        assertFalse(error.message!!.contains("expire", ignoreCase = true))
        assertTrue(error.message!!.contains("Open the invitation link again"))
        assertEquals(1, server.requestLines.size)
        assertNull(storage.referral.pending.load())
    }

    @Test
    fun `WL-S42 a pending answer asks to try again in a minute and keeps the invitation`() {
        val server = stub()
        val pending = Reply(503, PENDING_BODY, listOf("Retry-After: 1"))
        server.serveReplies(pending, pending, pending)
        val token = validToken()

        val error = assertIs<WarpLinkError.ServerError>(redeem(server, token).exceptionOrNull())

        assertEquals(503, error.statusCode)
        assertEquals(ReferralMessages.PENDING_INVITATION, error.message)
        assertTrue(error.message!!.contains("Try again in a minute"))
        assertFalse(ReferralMessages.isInvitationRefusal(error))
        val kept = assertNotNull(storage.referral.pending.load(), "a 503 must not clear the token")
        assertEquals(token, kept.token)
    }

    @Test
    fun `a matched redeem remembers the install and the token for a later claim`() {
        val server = stub()
        server.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        val token = validToken()

        val link = assertNotNull(redeem(server, token).getOrThrow())

        assertEquals(link, matches.single())
        val held = storage.referral.evidence.evidence()
        assertEquals(ReferralFixtures.INSTALL_ID, held.installId)
        assertEquals(token, held.recoveryToken)
        assertTrue(server.requestBodies.single().contains("\"recovery_token\":\"$token\""))
    }

    @Test
    fun `a redeem request carries the token and no device signals`() {
        val server = stub()
        server.serveReplies(Reply(200, TOKEN_MATCH_JSON))

        redeem(server, validToken())

        val body = server.requestBodies.single()
        assertFalse(body.contains("\"referrer\""))
        assertFalse(body.contains("\"fingerprint\""))
    }
}
