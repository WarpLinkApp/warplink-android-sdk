package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.WarpLinkError
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * First launch on a weak connection still attributes, and a run that never
 * succeeds leaves the gate open for the next launch.
 *
 * Driven at `performDeferredCheck`, the funnel the auto-fired and the manual
 * check share. BOTH of its branches are covered: the Play Install Referrer
 * branch and the raw-signals branch are the same request with a different body,
 * so a retry on only one of them would leave half of first-launch attribution
 * failing on exactly the connection this exists for.
 *
 * A 5xx drives most of these rather than a dropped connection, because it is
 * one connection per attempt: a dropped POST is re-sent once by the JDK itself,
 * so a drop costs two.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredRetryTest {

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
    fun `the raw signals branch retries a transient failure and leaves the gate open`() {
        val server = failing()

        val result = check(server)

        assertEquals(3, server.requestLines.size)
        assertIs<WarpLinkError.ServerError>(result.exceptionOrNull())
        assertTrue(server.requestBodies.none { it.contains(REFERRER_KEY) }, "not this branch")
        // Three spent attempts are still "did not complete". Closing the gate
        // here would cost a user who happened to be offline at launch their
        // attribution for good, and legs 16 and iL16 depend on it staying open.
        assertTrue(storage.deferredCheckAttempted)
        assertFalse(storage.deferredCheckCompleted)
    }

    @Test
    fun `the referrer branch retries a transient failure and leaves the gate open too`() {
        val server = failing()

        val result = check(server, referrer = freshReferrer())

        assertEquals(3, server.requestLines.size)
        assertIs<WarpLinkError.ServerError>(result.exceptionOrNull())
        assertTrue(server.requestBodies.all { it.contains("$REFERRER_KEY:\"$LINK_ID\"") })
        assertFalse(storage.deferredCheckCompleted)
    }

    @Test
    fun `a dropped connection then a success still delivers the deferred link`() {
        val server = stub(MATCH_JSON)
        // Attempt 1 is dropped, which the JDK re-sends once by itself, so it
        // costs two of these answers. Attempt 2 is served. Exactly as many
        // answers are armed as connections will arrive: one too few and the
        // spare attempt sits in the backlog for its whole read timeout.
        server.serveSequence(false, false, true)

        val result = check(server, referrer = freshReferrer())

        assertEquals(3, server.requestLines.size)
        assertEquals(LINK_ID, result.getOrThrow()?.linkId)
    }

    @Test
    fun `a retry exhausted check is tried again on the next launch`() {
        check(failing())

        val back = stub(MATCH_JSON)
        back.serveSequence(true)
        val result = check(back, referrer = freshReferrer())

        assertEquals(1, back.requestLines.size, "the next launch never reached the wire")
        assertEquals(LINK_ID, result.getOrThrow()?.linkId)
    }

    @Test
    fun `every attempt of one check carries the same tap id, and never in the body`() {
        val server = failing()

        check(server)

        val ids = tapIds(server)
        assertEquals(3, ids.size, "every attempt must carry the header")
        assertEquals(1, ids.toSet().size, "one check, one id")
        // Log-only on this endpoint. `app_installs.click_id` is uniquely
        // indexed, but the server derives its value from the KV deferred
        // payload rather than from the client, so nothing sent here dedupes an
        // install. The id only makes one check traceable across its own
        // retries, which is why it is never offered as a body field.
        assertTrue(server.requestBodies.none { it.contains(ids.first()) })
    }

    private fun stub(body: String = NO_MATCH_JSON, status: Int = 200) =
        LoopbackJsonServer(body, status = status).also { stubs.add(it) }

    /** A stub that answers 5xx to every attempt a full run can make. */
    private fun failing() = stub(status = 500).also { it.serveSequence(true, true, true) }

    /** Runs one deferred check against [server] and returns its single answer. */
    private fun check(
        server: LoopbackJsonServer,
        referrer: ReferrerSource? = null,
    ): Result<WarpLinkDeepLink?> {
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null
        var deliveries = 0
        performDeferredCheck(
            storage, FingerprintCollector(), ApiClient(API_KEY, server.baseUrl),
            referrer, null, isCurrentCheck = { true }, settings = RetrySettings.NO_WAIT,
        ) { deliveries++; result = it; done.countDown() }
        idleMainLooperUntil(done)
        assertEquals(1, deliveries, "one check must produce exactly one answer")
        return assertNotNull(result)
    }

    /** A referrer for an install that began now, so its match still routes. */
    private fun freshReferrer() = ReferrerSource {
        it(Result.success(ReferrerRead(LINK_ID, System.currentTimeMillis() / 1000)))
    }

    private fun tapIds(server: LoopbackJsonServer): List<String> =
        server.requestHeaders.mapNotNull { headers ->
            headers.firstOrNull { it.startsWith("X-WarpLink-Tap-Id:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        const val REFERRER_KEY = "\"referrer\""
        const val NO_MATCH_JSON = """{"matched":false}"""
        const val MATCH_JSON =
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","destination_url":"https://example.com/promo","deep_link_url":"myapp://promo/42"}"""
    }
}
