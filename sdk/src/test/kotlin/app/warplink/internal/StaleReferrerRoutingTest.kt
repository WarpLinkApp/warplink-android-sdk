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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Play Install Referrer outlives the install for months. With the 1.0.x gate
 * gone (LegacyUpgradeAttributionTest), a check that runs long after the install,
 * on a 1.0.x upgrade or in the first release a customer ships with the SDK, still
 * names the link the install came from, and the server matches it. Recording
 * that install is the point. Dropping a long-time user into months-old content
 * is not, and neither is caching it for attributionResult to replay.
 */
@RunWith(RobolectricTestRunner::class)
class StaleReferrerRoutingTest {

    private lateinit var context: Context
    private val stubs = mutableListOf<LoopbackJsonServer>()
    private val nowSeconds = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `a referrer from an install that began a month ago records the match but delivers no deep link`() {
        val delivered = runCheck(installBegan = nowSeconds - 30 * DAY)

        assertNull(delivered, "a long-time user was routed to content from a month ago")
        assertTrue(Storage(context).deferredCheckCompleted, "the match must still settle the check")
        assertNull(Storage(context).cachedAttribution, "nothing may be cached for attributionResult to replay")
    }

    @Test
    fun `a stale referrer still sends the attribution request`() {
        val stub = LoopbackJsonServer(MATCH_JSON).also { stubs.add(it) }
        stub.serveOnce()
        val done = CountDownLatch(1)

        performDeferredCheck(
            Storage(context), FingerprintCollector(), ApiClient(API_KEY, stub.baseUrl),
            ReferrerSource { it(Result.success(ReferrerRead(LINK_ID, nowSeconds - 30 * DAY))) }, null
        ) { done.countDown() }
        idleMainLooperUntil(done)

        // Recording the install IS the point. Only the routing is withheld.
        assertTrue(stub.requestReceived.await(5, TimeUnit.SECONDS), "no attribution request left the device")
    }

    @Test
    fun `a referrer from an install that began yesterday is routed`() {
        // The guard rail: a fresh Play install opened within the window is
        // exactly what deferred deep linking is for.
        val delivered = runCheck(installBegan = nowSeconds - DAY)

        assertEquals(LINK_ID, delivered?.linkId)
        assertEquals("https://example.com/promo", delivered?.destination)
        assertEquals(true, delivered?.matchGuaranteed)
        assertEquals(LINK_ID, Storage(context).cachedAttribution?.linkId)
    }

    @Test
    fun `a referrer with no install timestamp is routed`() {
        // Play did not say when the install began. Silence is not evidence of age.
        assertEquals(LINK_ID, runCheck(installBegan = 0L)?.linkId)
    }

    private fun runCheck(installBegan: Long): WarpLinkDeepLink? {
        val stub = LoopbackJsonServer(MATCH_JSON).also { stubs.add(it) }
        stub.serveOnce()
        val done = CountDownLatch(1)
        var delivered: Result<WarpLinkDeepLink?>? = null
        performDeferredCheck(
            Storage(context), FingerprintCollector(), ApiClient(API_KEY, stub.baseUrl),
            ReferrerSource { it(Result.success(ReferrerRead(LINK_ID, installBegan))) }, null
        ) { delivered = it; done.countDown() }
        idleMainLooperUntil(done)
        return delivered!!.getOrThrow()
    }

    private companion object {
        const val DAY = 24L * 60 * 60
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        const val MATCH_JSON = """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","destination_url":"https://example.com/promo","deep_link_url":"myapp://promo/42"}"""
    }
}
