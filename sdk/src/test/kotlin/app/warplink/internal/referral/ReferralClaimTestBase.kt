package app.warplink.internal.referral

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.LoopbackJsonServer.Reply
import app.warplink.ReferralClaimResult
import app.warplink.idleMainLooperUntil
import app.warplink.internal.ApiClient
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.referral.ReferralFixtures.API_KEY
import app.warplink.internal.referral.ReferralFixtures.INSTALLER_ID
import app.warplink.internal.referral.ReferralFixtures.LINK_ID
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNotNull

/** The stub servers, the storage and the claim call the claim tests share. */
@RunWith(RobolectricTestRunner::class)
abstract class ReferralClaimTestBase {

    private val stubs = mutableListOf<LoopbackJsonServer>()
    internal lateinit var storage: Storage

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

    internal fun track(server: LoopbackJsonServer) = server.also { stubs.add(it) }

    internal fun stub(vararg replies: Reply) =
        LoopbackJsonServer("{}").also {
            stubs.add(it)
            if (replies.isNotEmpty()) it.serveReplies(*replies)
        }

    internal fun claim(
        server: LoopbackJsonServer,
        installerId: String = INSTALLER_ID,
    ): Result<ReferralClaimResult> {
        val context = ReferralContext(
            ApiClient(API_KEY, server.baseUrl), storage, null, RetrySettings.NO_WAIT,
        ) { }
        val done = CountDownLatch(1)
        var result: Result<ReferralClaimResult>? = null
        ReferralClaimer.claim(installerId, context) { result = it; done.countDown() }
        idleMainLooperUntil(done)
        return assertNotNull(result)
    }

    internal companion object {
        const val NONE_JSON = """{"status":"none","referral_id":null,"link":null,"match":null,"flags":[],"duplicate_of":null,"updated":false,"replay_state":null}"""
        const val CLAIMED_JSON =
            """{"status":"claimed","referral_id":"ref-1","link":{"id":"$LINK_ID","slug":"promo","url":"https://acme.aplnk.to/promo"},"match":{"method":"install_token","exact":true,"confidence":1.0},"flags":[],"duplicate_of":null,"updated":false,"replay_state":"original"}"""
    }
}
