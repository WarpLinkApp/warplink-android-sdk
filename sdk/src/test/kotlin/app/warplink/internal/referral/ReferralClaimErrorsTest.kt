package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Reply
import app.warplink.ReferralClaimResult
import app.warplink.WarpLinkError
import app.warplink.internal.referral.ReferralFixtures.INSTALL_ID
import app.warplink.internal.referral.ReferralFixtures.INVALID_BODY
import app.warplink.internal.referral.ReferralFixtures.PENDING_BODY
import app.warplink.internal.referral.ReferralFixtures.validToken
import app.warplink.referral.ReferralClaimStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `claimReferral`: which answers are errors, what is kept and what is dropped. */
class ReferralClaimErrorsTest : ReferralClaimTestBase() {

    @Test
    fun `WL-S45 a refused token is none with no error and the token is dropped`() {
        val token = validToken()
        storage.referral.evidence.rememberInstall(INSTALL_ID)
        storage.referral.evidence.rememberToken(token, null)
        val server = stub(Reply(400, INVALID_BODY))

        val result = claim(server).getOrThrow()

        assertEquals(ReferralClaimStatus.NONE, result.status)
        assertEquals(1, server.requestLines.size, "a refusal must not be retried")
        val held = storage.referral.evidence.evidence()
        assertNull(held.recoveryToken)
        assertEquals(INSTALL_ID, held.installId, "the install id still names the install")
    }

    @Test
    fun `WL-S45 a token the server cannot confirm yet is an error and the token is kept`() {
        val token = validToken()
        storage.referral.evidence.rememberToken(token, null)
        val pending = Reply(503, PENDING_BODY, listOf("Retry-After: 1"))
        val server = stub(pending, pending, pending)

        val error = assertIs<WarpLinkError.ServerError>(claim(server).exceptionOrNull())

        assertEquals(ReferralMessages.PENDING_INVITATION, error.message)
        assertEquals(token, storage.referral.evidence.evidence().recoveryToken)
    }

    @Test
    fun `WL-S45 a key failure is an error`() {
        val error = claim(stub(Reply(403, """{"error":{"code":"FORBIDDEN","message":"no"}}""")))
            .exceptionOrNull()

        assertIs<WarpLinkError.InvalidApiKey>(error)
    }

    @Test
    fun `WL-S45 a network failure is an error`() {
        val server = track(LoopbackJsonServer("{}"))
        server.close()

        val error = claim(server).exceptionOrNull()

        assertIs<WarpLinkError.NetworkError>(error)
    }

    @Test
    fun `WL-S45 an installer id outside 1 to 128 characters is refused with no request`() {
        val server = stub()

        val empty = claim(server, installerId = "").exceptionOrNull()
        val long = claim(server, installerId = "x".repeat(129)).exceptionOrNull()

        assertEquals(400, assertIs<WarpLinkError.ServerError>(empty).statusCode)
        assertEquals(400, assertIs<WarpLinkError.ServerError>(long).statusCode)
        assertEquals(0, server.requestLines.size)
        assertIs<ReferralClaimResult>(claim(stub(Reply(200, NONE_JSON)), "x".repeat(128)).getOrThrow())
    }

    @Test
    fun `WL-S45 claiming twice sends the same claim and reads the same answer`() {
        storage.referral.evidence.rememberInstall(INSTALL_ID)
        val server = stub(Reply(200, CLAIMED_JSON), Reply(200, CLAIMED_JSON))

        val first = claim(server).getOrThrow()
        val second = claim(server).getOrThrow()

        assertEquals(2, server.requestBodies.size)
        assertEquals(server.requestBodies[0], server.requestBodies[1])
        assertEquals(first, second)
    }
}
