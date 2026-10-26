package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer.Reply
import app.warplink.internal.referral.ReferralFixtures.INSTALLER_ID
import app.warplink.internal.referral.ReferralFixtures.INSTALL_ID
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import app.warplink.internal.referral.ReferralFixtures.expiredToken
import app.warplink.internal.referral.ReferralFixtures.validToken
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReferralClaimStatus
import app.warplink.referral.ReplayState
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `claimReferral`: what it sends and what it decodes. */
class ReferralClaimerTest : ReferralClaimTestBase() {

    @Test
    fun `WL-S45 a claim sends the installer id with the install id and the token`() {
        val token = validToken()
        storage.referral.evidence.rememberInstall(INSTALL_ID)
        storage.referral.evidence.rememberToken(token, null)
        val server = stub(Reply(200, CLAIMED_JSON))

        val result = claim(server).getOrThrow()

        val body = JSONObject(server.requestBodies.single())
        assertEquals(INSTALLER_ID, body.getString("installer_id"))
        assertEquals(INSTALL_ID, body.getString("install_id"))
        assertEquals(token, body.getString("recovery_token"))
        assertEquals("android", body.getString("platform"))
        assertTrue(server.requestLines.single().contains("/referrals/claims"))
        assertEquals(ReferralClaimStatus.CLAIMED, result.status)
        assertEquals("ref-1", result.referralId)
        assertEquals(LINK_ID, result.linkId)
        assertEquals(MatchMethod.INSTALL_TOKEN, result.matchMethod)
        assertTrue(result.isExact)
        assertEquals(ReplayState.ORIGINAL, result.replayState)
    }

    @Test
    fun `WL-S45 an expired token and an install id that is not a UUID are left out`() {
        storage.referral.evidence.rememberInstall("not-a-uuid")
        val expired = expiredToken()
        storage.referral.evidence.rememberToken(expired, ReferralInvitation.parse(expired)?.expiresAtSeconds)
        val server = stub(Reply(200, NONE_JSON))

        claim(server).getOrThrow()

        val body = JSONObject(server.requestBodies.single())
        assertFalse(body.has("install_id"))
        assertFalse(body.has("recovery_token"))
        assertEquals(INSTALLER_ID, body.getString("installer_id"))
    }

    @Test
    fun `WL-S45 a claim with nothing held sends the installer id alone`() {
        val server = stub(Reply(200, NONE_JSON))

        claim(server).getOrThrow()

        val body = JSONObject(server.requestBodies.single())
        assertFalse(body.has("install_id"))
        assertFalse(body.has("recovery_token"))
    }

    @Test
    fun `WL-S45 status none is a result and not an error`() {
        val result = claim(stub(Reply(200, NONE_JSON)))

        assertEquals(ReferralClaimStatus.NONE, result.getOrThrow().status)
        assertNull(result.getOrThrow().referralId)
    }

    @Test
    fun `WL-S45 an unreadable answer is none and not an error`() {
        assertEquals(
            ReferralClaimStatus.NONE,
            claim(stub(Reply(200, "<html>not json</html>"))).getOrThrow().status,
        )
        assertEquals(ReferralClaimStatus.NONE, claim(stub(Reply(200, "{}"))).getOrThrow().status)
        assertEquals(
            ReferralClaimStatus.NONE,
            claim(stub(Reply(200, """{"status":"surprise"}"""))).getOrThrow().status,
        )
    }
}
