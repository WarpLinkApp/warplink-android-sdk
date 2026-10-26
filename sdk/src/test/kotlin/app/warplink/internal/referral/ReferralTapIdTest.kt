package app.warplink.internal.referral

import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Reply
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.ReferrerRead
import app.warplink.internal.ReferrerSource
import app.warplink.internal.RetrySettings
import app.warplink.internal.performDeferredCheck
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.INVALID_BODY
import app.warplink.internal.referral.ReferralFixtures.LEGACY_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import app.warplink.internal.referral.ReferralFixtures.PENDING_BODY
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.validToken
import app.warplink.referral.ReplayState
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A token redeem carries no device id and no signals, so the tap id kept with
 * the token is what lets the server read the person's own retry as the
 * original. One id per token, on every redeem of it, on every launch.
 */
class ReferralTapIdTest : ReferralRedeemTestBase() {

    @Test
    fun `WL-S44 a pasted token keeps one tap id across a lost response and the next launch`() {
        val token = validToken()
        val first = stub()
        first.serveReplies(Reply(500, "{}"), Reply(500, "{}"), Reply(500, "{}"))
        redeem(first, token)

        val second = stub()
        second.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        val link = assertNotNull(resume(second).getOrThrow())

        val ids = tapIds(first) + tapIds(second)
        assertEquals(4, ids.size)
        assertEquals(1, ids.toSet().size, "the retry must read as the same installation")
        assertEquals(ReplayState.ORIGINAL, link.replayState)
    }

    @Test
    fun `WL-S44 pasting the same invitation again keeps its tap id`() {
        val token = validToken()
        val first = stub()
        first.serveReplies(Reply(500, "{}"), Reply(500, "{}"), Reply(500, "{}"))
        redeem(first, token)

        val again = stub()
        again.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        redeem(again, token)

        assertEquals(tapIds(first).first(), tapIds(again).single())
    }

    @Test
    fun `WL-S44 pasting the token the referrer carried keeps the tap id the referrer sent`() {
        val token = validToken()
        val pending = Reply(503, PENDING_BODY)
        val referrerLaunch = stub().also { it.serveReplies(pending, pending, pending) }
        check(referrerLaunch, token)

        val paste = stub().also { it.serveReplies(Reply(200, TOKEN_MATCH_JSON)) }
        redeem(paste, token)

        assertEquals(tapIds(referrerLaunch).first(), tapIds(paste).single())
    }

    @Test
    fun `WL-S44 a different invitation gets its own tap id and the id is dropped with the token`() {
        val first = stub()
        first.serveReplies(Reply(400, INVALID_BODY))
        redeem(first, validToken(filler = 1))
        assertNull(storage.referral.pending.load())

        val second = stub()
        second.serveReplies(Reply(200, TOKEN_MATCH_JSON))
        redeem(second, validToken(filler = 2))

        assertNotEquals(tapIds(first).single(), tapIds(second).single())
    }

    @Test
    fun `WL-S44 the referrer token keeps one tap id when the first launch could not settle it`() {
        val token = validToken()
        val pending = Reply(503, PENDING_BODY)
        val first = stub().also { it.serveReplies(pending, pending, pending) }
        check(first, token)

        val second = stub().also { it.serveReplies(Reply(200, TOKEN_MATCH_JSON)) }
        val link = assertNotNull(check(second, token).getOrThrow())

        val ids = tapIds(first) + tapIds(second)
        assertEquals(4, ids.size)
        assertEquals(1, ids.toSet().size, "the next launch must read as the same installation")
        assertEquals(ReplayState.ORIGINAL, link.replayState)
    }

    @Test
    fun `WL-S42 a refused referrer token drops its tap id and the link id match carries its own`() {
        val token = validToken()
        val server = stub().also { it.serveReplies(Reply(400, INVALID_BODY), Reply(200, LEGACY_MATCH_JSON)) }
        check(server, token)

        assertEquals(2, tapIds(server).size)
        assertNotEquals(tapIds(server)[0], tapIds(server)[1])
        val next = stub().also { it.serveReplies(Reply(200, TOKEN_MATCH_JSON)) }
        check(next, token)
        assertNotEquals(tapIds(server)[0], tapIds(next).single(), "a refusal clears the kept id")
    }

    private fun tapIds(server: LoopbackJsonServer): List<String> = server.requestHeaders.map { headers ->
        headers.first { it.startsWith("X-WarpLink-Tap-Id:", ignoreCase = true) }
            .substringAfter(":").trim()
    }

    /** One deferred check whose Play referrer carries [token] and the link id. */
    private fun check(server: LoopbackJsonServer, token: String): Result<WarpLinkDeepLink?> {
        val referrer = ReferrerSource {
            it(Result.success(ReferrerRead(LINK_ID, System.currentTimeMillis() / 1000, token)))
        }
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        storage.deferredCheckCompleted = false
        performDeferredCheck(
            storage, FingerprintCollector(), ApiClient(API_KEY, server.baseUrl),
            referrer, null, isCurrentCheck = { true }, settings = RetrySettings.NO_WAIT,
        ) { result = it; done.countDown() }
        idleMainLooperUntil(done)
        return assertNotNull(result)
    }
}
