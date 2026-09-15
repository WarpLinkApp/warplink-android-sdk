package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deferred check runs once per install, and [handleAttributionResponse]
 * closes that gate on any 200. A match the SDK cannot route is not a definitive
 * answer: it names no link and carries no destination, so the same function
 * discards it two lines later and reports no match to the host. Spending the one
 * attempt on it ends attribution for the whole install.
 *
 * The server emits exactly this body from its device_id branch once the link an
 * install was attributed to has been deleted. Same defect as iOS, same ordering.
 */
@RunWith(RobolectricTestRunner::class)
class RoutelessMatchTest {

    private lateinit var context: Context
    private lateinit var storage: Storage
    private val servers = mutableListOf<LoopbackJsonServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    @Test
    fun `a routeless match leaves the gate open`() {
        deferredCheck(against = ROUTELESS_BODY)

        assertFalse(
            storage.deferredCheckCompleted,
            "a response with no link and no destination is not a definitive answer"
        )
    }

    @Test
    fun `a routeless match is retried on the next launch`() {
        deferredCheck(against = ROUTELESS_BODY)

        // The next launch of the same install builds a fresh client.
        val relaunch = serve(MATCHED_BODY)
        deferredCheck(against = relaunch)

        assertTrue(
            relaunch.requestReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS),
            "the install has never been attributed, so the check must run again"
        )
    }

    @Test
    fun `a retried check can still be attributed`() {
        deferredCheck(against = ROUTELESS_BODY)

        val result = deferredCheck(against = serve(MATCHED_BODY))

        assertEquals("link-abc-123", result?.getOrNull()?.linkId)
    }

    /**
     * A genuine no-match is still definitive and must still close the gate.
     * Without this, a fix that simply stopped writing the gate would trade one
     * defect for a re-fire on every launch.
     */
    @Test
    fun `a confirmed no-match still closes the gate`() {
        deferredCheck(against = NO_MATCH_BODY)

        assertTrue(storage.deferredCheckCompleted)
    }

    /**
     * `matched` is authoritative, not the fields beside it. A body that says no
     * while still carrying routing is a no-match, and routing it would attribute
     * an install the server explicitly declined to attribute.
     *
     * Not a shape the server emits. It is here because without it the `matched`
     * check inside [routableDeepLink] is unverified: every other test sends a
     * body whose link and destination are absent anyway, so the two null checks
     * beside it would carry the whole decision.
     */
    @Test
    fun `an explicit no-match is not routed even when it carries routing`() {
        val result = deferredCheck(against = NO_MATCH_WITH_ROUTING_BODY)

        assertNull(result?.getOrNull())
        assertTrue(storage.deferredCheckCompleted, "a no-match is still definitive")
    }

    // Helpers

    private fun serve(body: String): LoopbackJsonServer =
        LoopbackJsonServer(body).also {
            servers.add(it)
            it.serveOnce()
        }

    private fun deferredCheck(against: String): Result<WarpLinkDeepLink?>? =
        deferredCheck(against = serve(against))

    private fun deferredCheck(against: LoopbackJsonServer): Result<WarpLinkDeepLink?>? {
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null

        performDeferredCheck(
            storage,
            FingerprintCollector(),
            ApiClient(API_KEY, against.baseUrl),
            null,
            null
        ) { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)
        return result
    }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val AWAIT_SECONDS = 5L
        const val NO_MATCH_BODY = """{"matched":false}"""

        /** `matched: true` with nothing to route to. */
        const val ROUTELESS_BODY = """{"matched":true,"match_type":"probabilistic",""" +
            """"match_confidence":0.85,"match_guaranteed":false,"link_id":null,""" +
            """"deep_link_url":null,"destination_url":null,"custom_params":{},""" +
            """"install_id":"inst-456","app_id":null}"""

        const val NO_MATCH_WITH_ROUTING_BODY =
            """{"matched":false,"link_id":"link-abc-123",""" +
                """"destination_url":"https://example.com/42"}"""

        const val MATCHED_BODY = """{"matched":true,"match_type":"probabilistic",""" +
            """"match_confidence":0.85,"link_id":"link-abc-123",""" +
            """"deep_link_url":"myapp://content/42",""" +
            """"destination_url":"https://example.com/42",""" +
            """"custom_params":{},"install_id":"inst-456"}"""
    }
}
