package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.KeyEnvironment
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.referral.ReferralFixtures.TOKEN_MATCH_JSON
import app.warplink.internal.referral.ReferralFixtures.validToken
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Configuring again with other options retires the redeem the previous session had on the wire, even for the same key. */
@RunWith(RobolectricTestRunner::class)
class ReconfigureRedeemTest {

    private val key = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    private val api = LoopbackJsonServer(TOKEN_MATCH_JSON)
    private val release = CountDownLatch(1)
    private lateinit var context: Context

    @Before
    fun setUp() {
        WarpLink.reset()
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        context = ApplicationProvider.getApplicationContext()
        Storage(context, KeyEnvironment.TEST).clearAll()
    }

    @After
    fun tearDown() {
        release.countDown()
        api.close()
        WarpLink.reset()
    }

    @Test
    fun `a redeem answered after a reconfigure with other options delivers no match`() {
        configure(debug = false)
        api.serveOnce(gate = release)
        var answer: Result<WarpLinkDeepLink?>? = null
        val finished = CountDownLatch(1)
        WarpLink.redeemInvitation(validToken()) { answer = it; finished.countDown() }
        idleMainLooperUntil(message = "the redeem never reached the wire") { api.requestReceived.count == 0L }

        configure(debug = true)
        release.countDown()
        idleMainLooperUntil(finished)

        assertNull(answer?.getOrThrow())
        assertNull(WarpLink.attributionResult)
        assertNotNull(Storage(context, KeyEnvironment.TEST).referral.pending.load(), "the invitation stays held for the next launch")
    }

    /** The same key with other options is a new configuration: it rebuilds the session. */
    private fun configure(debug: Boolean) {
        Storage(context, KeyEnvironment.TEST).apiKeyValidatedAt = System.currentTimeMillis()
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl, debugLogging = debug,
                automaticDeepLinks = false, automaticDeferredDeepLinks = false
            )
        )
    }
}
