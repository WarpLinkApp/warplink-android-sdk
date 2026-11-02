package app.warplink.internal.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNotNull

/** The stub servers, the storage and the redeem and resume calls the redeem tests share. */
@RunWith(RobolectricTestRunner::class)
abstract class ReferralRedeemTestBase {

    private val stubs = mutableListOf<LoopbackJsonServer>()
    internal val matches = mutableListOf<WarpLinkDeepLink>()
    internal lateinit var context: Context
    internal lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    internal fun expiryOf(token: String) = assertNotNull(ReferralInvitation.parse(token)).expiresAtSeconds

    internal fun stub(status: Int = 200) =
        LoopbackJsonServer("{}", status = status).also { stubs.add(it) }

    internal fun contextFor(server: LoopbackJsonServer) = ReferralContext(
        ApiClient(API_KEY, server.baseUrl), storage, null, RetrySettings.NO_WAIT,
    ) { matches.add(it); {} }

    internal fun redeem(server: LoopbackJsonServer, text: String): Result<WarpLinkDeepLink?> =
        await { done -> ReferralRedeemer.redeem(text, contextFor(server), done) }

    internal fun resume(server: LoopbackJsonServer): Result<WarpLinkDeepLink?> =
        await { done -> ReferralRedeemer.resume(contextFor(server), done) }

    /** Resumes where an answer is not expected, for the cases that send nothing. */
    internal fun resumeQuietly(server: LoopbackJsonServer) {
        ReferralRedeemer.resume(contextFor(server)) { }
        idleBriefly()
    }

    private fun idleBriefly() {
        val deadline = System.nanoTime() + 200_000_000L
        idleMainLooperUntil(timeoutMs = 1_000) { System.nanoTime() >= deadline }
    }

    private fun await(start: ((Result<WarpLinkDeepLink?>) -> Unit) -> Unit): Result<WarpLinkDeepLink?> {
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        start { result = it; done.countDown() }
        idleMainLooperUntil(done)
        return assertNotNull(result)
    }
}
