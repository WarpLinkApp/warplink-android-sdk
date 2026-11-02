package app.warplink

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.KeyEnvironment
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.UriParser
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A callback answered by `clearAttribution` or by a reconfigure can itself call
 * `configure`. What the outer call does next must belong to the session it
 * started with, never to the key the callback switched to.
 */
@RunWith(RobolectricTestRunner::class)
class ClearAndConfigureRaceTest {

    private val testKey = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    private val liveKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val testApi = LoopbackJsonServer("""{"matched":false}""")
    private val liveApi = LoopbackJsonServer("""{"matched":false}""")
    private val release = CountDownLatch(1)

    @Before
    fun setUp() {
        WarpLink.reset()
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        context = ApplicationProvider.getApplicationContext()
        KeyEnvironment.values().forEach { Storage(context, it).also { storage ->
            storage.clearAll()
            storage.apiKeyValidatedAt = System.currentTimeMillis()
        } }
    }

    @After
    fun tearDown() {
        release.countDown()
        testApi.close()
        liveApi.close()
        WarpLink.reset()
    }

    @Test
    fun `a clear never restarts the automatic check on a live session a parked callback configured`() {
        configure(testKey, testApi, automaticDeferredDeepLinks = true)
        testApi.serveOnce(gate = release)
        idleMainLooperUntil(message = "the first automatic check never reached the wire") { testApi.requestReceived.count == 0L }
        liveApi.serveOnce()
        WarpLink.checkDeferredDeepLink {
            configure(liveKey, liveApi, automaticDeferredDeepLinks = false)
        }

        assertTrue(WarpLink.clearAttribution().isSuccess)
        idleFor(settleMs = 300)

        assertEquals(1L, liveApi.requestReceived.count, "the live session received ${liveApi.requestLines}")
    }

    @Test
    fun `an older configure cannot overwrite the domains a newer one declared`() {
        configure(testKey, testApi)
        testApi.serveOnce(gate = release)
        WarpLink.checkDeferredDeepLink { }
        idleMainLooperUntil(message = "the check never reached the wire") { testApi.requestReceived.count == 0L }
        var reconfigured = false
        WarpLink.checkDeferredDeepLink {
            if (reconfigured) return@checkDeferredDeepLink
            reconfigured = true
            configure(testKey, testApi, linkDomains = listOf("newer.example.com"))
        }

        configure(liveKey, liveApi, linkDomains = listOf("older.example.com"))

        assertTrue(reconfigured)
        assertTrue(WarpLink.isWarpLinkUri(Uri.parse("https://newer.example.com/abc")))
        assertFalse(WarpLink.isWarpLinkUri(Uri.parse("https://older.example.com/abc")))
        UriParser.resetKnownDomains()
    }

    private fun idleFor(settleMs: Long) {
        val deadline = System.currentTimeMillis() + settleMs
        idleMainLooperUntil { System.currentTimeMillis() >= deadline }
    }

    private fun configure(
        key: String,
        api: LoopbackJsonServer,
        automaticDeferredDeepLinks: Boolean = false,
        linkDomains: List<String> = emptyList(),
    ) = WarpLink.configure(
        context, key,
        WarpLinkOptions(
            apiEndpoint = api.baseUrl,
            automaticDeepLinks = false,
            automaticDeferredDeepLinks = automaticDeferredDeepLinks,
            linkDomains = linkDomains,
        )
    )
}
