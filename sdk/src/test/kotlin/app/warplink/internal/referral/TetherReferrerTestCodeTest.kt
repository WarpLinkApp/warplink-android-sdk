package app.warplink.internal.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Reply
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.FingerprintCollector
import app.warplink.internal.ReferrerRead
import app.warplink.internal.ReferrerSource
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.performDeferredCheck
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.LEGACY_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Tether test code in the Play referrer: Android sends it alone, keeps the
 * receipt in memory, records nothing, and never lets it hold up the link match.
 */
@RunWith(RobolectricTestRunner::class)
class TetherReferrerTestCodeTest {

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
    fun `WL-S60 a test code is sent alone as tether_code and the receipt is kept`() {
        val server = stub(Reply(200, TEST_ANSWER), Reply(200, LEGACY_MATCH_JSON))

        val link = assertNotNull(check(server, TEST_CODE).getOrThrow())

        val body = server.requestBodies.first()
        assertTrue(body.contains("\"tether_code\":\"$TEST_CODE\""))
        assertTrue(body.contains("\"sdk_platform\":\"android\""))
        assertFalse(body.contains("\"referrer\""), "the link id must not ride with the code")
        assertFalse(body.contains("recovery_token"))
        assertFalse(body.contains("tether_read_mode"), "Android has no read mode")
        assertEquals("rcpt_1", storage.referral.tetherTestReceipt)
        assertEquals(LINK_ID, link.linkId)
    }

    @Test
    fun `WL-S60 a test code never blocks the link id match behind it`() {
        val server = stub(Reply(200, TEST_ANSWER), Reply(200, LEGACY_MATCH_JSON))

        check(server, TEST_CODE)

        assertEquals(2, server.requestBodies.size)
        assertTrue(server.requestBodies[1].contains("\"referrer\":\"$LINK_ID\""))
        assertFalse(server.requestBodies[1].contains("tether_code"))
    }

    @Test
    fun `WL-S60 a refused test code is tried once and the check goes on`() {
        val server = stub(Reply(400, REFUSED), Reply(200, LEGACY_MATCH_JSON))

        val link = assertNotNull(check(server, TEST_CODE).getOrThrow())

        assertEquals(2, server.requestBodies.size)
        assertNull(storage.referral.tetherTestReceipt)
        assertEquals(LINK_ID, link.linkId)
    }

    @Test
    fun `WL-S60 an answer that is not a test keeps no receipt`() {
        val server = stub(Reply(200, NOT_TEST_ANSWER), Reply(200, LEGACY_MATCH_JSON))

        check(server, TEST_CODE)

        assertNull(storage.referral.tetherTestReceipt)
    }

    @Test
    fun `WL-S61 a test code stores no recovery token`() {
        val server = stub(Reply(200, TEST_ANSWER), Reply(200, LEGACY_MATCH_JSON))

        check(server, TEST_CODE)

        val held = storage.referral.evidence.evidence()
        assertNull(held.recoveryToken)
    }

    @Test
    fun `WL-S61 clearing the attribution forgets the receipt`() {
        storage.referral.tetherTestReceipt = "rcpt_1"

        assertTrue(storage.clearAttribution())

        assertNull(storage.referral.tetherTestReceipt)
    }

    @Test
    fun `WL-S60 a live code in the referrer is not read as a test code`() {
        val server = stub(Reply(200, LEGACY_MATCH_JSON))

        check(server, "wl1_abc")

        assertFalse(server.requestBodies.any { it.contains("tether_code") })
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

    private companion object {
        const val TEST_CODE = "wl1t_abcDEF123"
        const val TEST_ANSWER =
            """{"matched":false,"tether":{"status":"test","accepted":false,"test":true,"test_receipt":"rcpt_1"}}"""
        const val NOT_TEST_ANSWER =
            """{"matched":false,"tether":{"status":"not_found","accepted":false,"test":false,"test_receipt":null}}"""
        const val REFUSED = """{"error":{"code":"VALIDATION_ERROR","message":"bad"}}"""
    }
}
