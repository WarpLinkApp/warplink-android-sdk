package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WL-S70: `configure` with the same key and equal options as the live session
 * changes nothing and keeps the check it is running. The `onLink` lambda is not
 * part of the comparison, a host that configures in `onCreate` passes a new one
 * each time, and the newest one gets the results. A different key, other options
 * or a different choice of having a sink rebuilds the session.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigureIdempotentTest {

    private val liveKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val otherKey = "wl_live_ZYXWVUTSRQPONMLKJIHGFEDCBA987654"
    private lateinit var context: Context
    private val api = LoopbackJsonServer(MATCH)
    private val release = CountDownLatch(1)
    private val onLink: (Result<WarpLinkDeepLink>) -> Unit = { }

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        // A fresh validation is cached, so the only request the stub sees is the match.
        Storage(context).apply {
            clearAll()
            apiKeyValidatedAt = System.currentTimeMillis()
        }
        api.serveOnce(gate = release)
    }

    @After
    fun tearDown() {
        release.countDown()
        api.close()
        WarpLink.reset()
    }

    @Test
    fun `WL-S70 the same key and options keep the check on the wire`() {
        configure(liveKey)
        awaitRequest()
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        configure(liveKey)

        assertEquals(null, parked, "a parked caller must not be answered: nothing was abandoned")
        release.countDown()
        idleMainLooperUntil(message = "the parked caller never got the check's answer") { parked != null }
        assertEquals(1, api.requestLines.size, "one check, one request")
        assertTrue(WarpLink.isAttributionComplete())
    }

    @Test
    fun `WL-S70 an equal call with a new onLink lambda keeps the check and sends the result to the new lambda`() {
        val firstSeen = mutableListOf<Result<WarpLinkDeepLink>>()
        val secondSeen = mutableListOf<Result<WarpLinkDeepLink>>()
        configure(liveKey, sink = { firstSeen.add(it) })
        awaitRequest()
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        configure(liveKey, sink = { secondSeen.add(it) })

        assertEquals(null, parked, "nothing was abandoned")
        release.countDown()
        idleMainLooperUntil(message = "the new lambda never got the deferred match") { secondSeen.isNotEmpty() }
        assertEquals(1, api.requestLines.size, "one check, one request")
        assertEquals(1, secondSeen.size)
        assertTrue(firstSeen.isEmpty(), "the replaced lambda gets nothing")
    }

    @Test
    fun `WL-S70 a call that drops the onLink lambda rebuilds the session and abandons the check`() {
        configure(liveKey)
        awaitRequest()
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        configure(liveKey, sink = null)

        assertTrue(parked!!.isSuccess, "the abandoned check answers its parked caller")
    }

    @Test
    fun `WL-S70 a different key rebuilds the session and abandons the check`() {
        configure(liveKey)
        awaitRequest()
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        configure(otherKey)

        assertTrue(parked!!.isSuccess, "the abandoned check answers its parked caller")
        assertEquals(null, parked!!.getOrNull())
    }

    @Test
    fun `WL-S70 other options rebuild the session and abandon the check`() {
        configure(liveKey)
        awaitRequest()
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        configure(liveKey, debug = true)

        assertTrue(parked!!.isSuccess)
    }

    private fun awaitRequest() =
        idleMainLooperUntil(message = "the auto check never reached the wire") { api.requestReceived.count == 0L }

    private fun configure(
        key: String,
        debug: Boolean = false,
        sink: ((Result<WarpLinkDeepLink>) -> Unit)? = onLink,
    ) {
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl, debugLogging = debug,
                automaticDeepLinks = false, automaticDeferredDeepLinks = true, onLink = sink
            )
        )
    }

    private companion object {
        const val MATCH =
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"lnk_early","destination_url":"https://example.com/promo"}"""
    }
}
