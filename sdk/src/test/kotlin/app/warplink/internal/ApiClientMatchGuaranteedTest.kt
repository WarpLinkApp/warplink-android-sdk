package app.warplink.internal

import android.os.Looper
import app.warplink.LoopbackJsonServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `match_guaranteed` is the one field a host app is told to branch on, so both
 * directions need pinning: a true flag must survive the parse, and a response
 * that omits it must never read as guaranteed.
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientMatchGuaranteedTest {

    private var api: LoopbackJsonServer? = null

    @After
    fun tearDown() {
        api?.close()
    }

    private fun matchAgainst(body: String): AttributionResponse {
        val server = LoopbackJsonServer(body).also { api = it }
        server.serveOnce()

        val client = ApiClient("wl_live_abcdefghijklmnopqrstuvwxyz012345", server.baseUrl)
        val done = CountDownLatch(1)
        var captured: AttributionResponse? = null

        client.matchAttribution(
            DeviceSignals("en-US", -300, "America/Toronto"), "1.1.0", null,
            isReinstall = false
        ) { r ->
            captured = r.getOrNull()
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        // ApiClient posts its callback to the main looper, which Robolectric
        // only runs when idled.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(captured) { "callback never delivered a response" }
    }

    @Test
    fun `parses a true match_guaranteed from the server`() {
        val parsed = matchAgainst(
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,
                "link_id":"11111111-1111-1111-1111-111111111111","match_guaranteed":true}"""
        )

        assertTrue(parsed.matchGuaranteed)
    }

    @Test
    fun `defaults to false when the server omits match_guaranteed`() {
        val parsed = matchAgainst(
            """{"matched":true,"match_type":"probabilistic","match_confidence":0.5,
                "link_id":"11111111-1111-1111-1111-111111111111"}"""
        )

        // Missing must never read as guaranteed: a host gating a sensitive
        // action on it would over-trust a probabilistic guess.
        assertFalse(parsed.matchGuaranteed)
    }
}
