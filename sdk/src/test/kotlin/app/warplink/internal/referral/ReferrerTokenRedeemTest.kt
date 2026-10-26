package app.warplink.internal.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Reply
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.ReferrerRead
import app.warplink.internal.ReferrerSource
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.performDeferredCheck
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.EXPIRED_BODY
import app.warplink.internal.referral.ReferralFixtures.INSTALL_ID
import app.warplink.internal.referral.ReferralFixtures.INVALID_BODY
import app.warplink.internal.referral.ReferralFixtures.LEGACY_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import app.warplink.internal.referral.ReferralFixtures.PENDING_BODY
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.expiredToken
import app.warplink.internal.referral.ReferralFixtures.validToken
import app.warplink.referral.EvidenceClass
import app.warplink.referral.MatchMethod
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recovery token in the Play referrer, redeemed on the first launch before
 * any estimate, and what a refusal or a pending answer leaves behind.
 */
@RunWith(RobolectricTestRunner::class)
class ReferrerTokenRedeemTest {

    private val stubs = mutableListOf<LoopbackJsonServer>()
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `WL-S40 the referrer token is sent alone as recovery_token and matches exactly`() {
        val token = validToken()
        val server = stub(Reply(200, TOKEN_MATCH_JSON))

        val link = assertNotNull(check(server, token).getOrThrow())

        val body = server.requestBodies.single()
        assertTrue(body.contains("\"recovery_token\":\"$token\""))
        assertFalse(body.contains("\"referrer\""), "the link id must not ride with the token")
        assertEquals(MatchMethod.INSTALL_TOKEN, link.matchMethod)
        assertEquals(EvidenceClass.EXACT, link.evidenceClass)
        val held = storage.referral.evidence.evidence()
        assertEquals(token, held.recoveryToken)
        assertEquals(INSTALL_ID, held.installId)
    }

    @Test
    fun `WL-S42 a refused token falls through to the link id match and is dropped`() {
        val token = validToken()
        storage.referral.evidence.rememberToken(token, null)
        val server = stub(Reply(400, INVALID_BODY), Reply(200, LEGACY_MATCH_JSON))

        val link = assertNotNull(check(server, token).getOrThrow())

        assertEquals(2, server.requestBodies.size)
        assertTrue(server.requestBodies[1].contains("\"referrer\":\"$LINK_ID\""))
        assertFalse(server.requestBodies[1].contains("recovery_token"), "a refused token is not sent again")
        assertNull(storage.referral.evidence.evidence().recoveryToken)
        assertEquals(LINK_ID, link.linkId)
    }

    @Test
    fun `WL-S42 a paused or ended link also falls through and is dropped`() {
        val token = validToken()
        storage.referral.evidence.rememberToken(token, null)
        val server = stub(Reply(400, EXPIRED_BODY), Reply(200, LEGACY_MATCH_JSON))

        assertNotNull(check(server, token).getOrThrow())

        assertNull(storage.referral.evidence.evidence().recoveryToken)
    }

    @Test
    fun `WL-S42 a pending token ends the check without completing it and keeps the token`() {
        val token = validToken()
        storage.referral.evidence.rememberToken(token, null)
        val pending = Reply(503, PENDING_BODY, listOf("Retry-After: 1"))
        val server = stub(pending, pending, pending)

        val error = assertIs<WarpLinkError.ServerError>(check(server, token).exceptionOrNull())

        assertEquals(ReferralMessages.PENDING_INVITATION, error.message)
        assertEquals(3, server.requestBodies.size)
        assertTrue(server.requestBodies.none { it.contains("\"referrer\"") })
        assertFalse(storage.deferredCheckCompleted, "the next launch must try again")
        assertEquals(token, storage.referral.evidence.evidence().recoveryToken)
    }

    @Test
    fun `WL-S42 the next launch redeems the same referrer once the token is confirmed`() {
        val token = validToken()
        val pending = Reply(503, PENDING_BODY)
        check(stub(pending, pending, pending), token)

        val server = stub(Reply(200, TOKEN_MATCH_JSON))
        val link = assertNotNull(check(server, token).getOrThrow())

        assertEquals(MatchMethod.INSTALL_TOKEN, link.matchMethod)
        assertTrue(storage.deferredCheckCompleted)
    }

    @Test
    fun `WL-S42 an expired referrer token is not sent and the link id match goes on`() {
        val server = stub(Reply(200, LEGACY_MATCH_JSON))

        val link = assertNotNull(check(server, expiredToken()).getOrThrow())

        assertEquals(1, server.requestBodies.size)
        assertFalse(server.requestBodies.single().contains("recovery_token"))
        assertEquals(LINK_ID, link.linkId)
    }

    @Test
    fun `WL-S46 a link id match with no token stays legacy and is not upgraded`() {
        val server = stub(Reply(200, LEGACY_MATCH_JSON))

        val link = assertNotNull(check(server, token = null).getOrThrow())

        assertEquals(MatchMethod.LINK_OPEN, link.matchMethod)
        assertEquals(EvidenceClass.LEGACY_LINK, link.evidenceClass)
        assertNull(storage.referral.evidence.evidence().recoveryToken)
    }

    private fun stub(vararg replies: Reply) =
        LoopbackJsonServer("{}").also {
            stubs.add(it)
            it.serveReplies(*replies)
        }

    /** One deferred check whose Play referrer carries [token] and the link id. */
    private fun check(server: LoopbackJsonServer, token: String?): Result<WarpLinkDeepLink?> {
        val referrer = ReferrerSource {
            it(Result.success(ReferrerRead(LINK_ID, System.currentTimeMillis() / 1000, token)))
        }
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        performDeferredCheck(
            storage, FingerprintCollector(), ApiClient(API_KEY, server.baseUrl),
            referrer, null, isCurrentCheck = { true }, settings = RetrySettings.NO_WAIT,
        ) { result = it; done.countDown() }
        idleMainLooperUntil(done)
        return assertNotNull(result)
    }
}
