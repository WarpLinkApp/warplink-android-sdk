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
import java.util.SimpleTimeZone
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The unit test beside [FingerprintCollectorTest] proves the collector keeps
 * the surviving signals. It does not prove the thing that actually mattered:
 * that the request still leaves the device.
 *
 * A zone with no tzdb entry used to make the whole signal set fail, and the
 * deferred check returned before ever calling the API. The install went
 * unattributed with no request, no server-side attempt row, and nothing
 * logged. This drives the real path end to end against a loopback API and
 * asserts a request arrives.
 */
@RunWith(RobolectricTestRunner::class)
class DegradedSignalsStillMatchTest {

    private lateinit var context: Context
    private lateinit var storage: Storage
    private val originalTz = TimeZone.getDefault()
    private var api: LoopbackJsonServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
        api?.close()
    }

    @Test
    fun `WL-S20 an unresolvable zone still sends the attribution request`() {
        // The trigger: a process default zone whose id has no tzdb entry.
        TimeZone.setDefault(SimpleTimeZone(19800000, "MyCustomTZ"))
        val server = LoopbackJsonServer(NO_MATCH_BODY).also { api = it }
        server.serveOnce()
        val latch = CountDownLatch(1)

        performDeferredCheck(
            storage,
            FingerprintCollector(),
            ApiClient(API_KEY, server.baseUrl),
            null,
            null
        ) { latch.countDown() }

        idleMainLooperUntil(latch)

        assertTrue(
            server.requestReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS),
            "the deferred check sent no request at all"
        )
        // The zone is dropped rather than sent as a value the schema rejects,
        // while the two signals beside it still travel.
        val body = server.requestBody
        assertFalse(body.contains("MyCustomTZ"), "sent a non-tzdb zone: $body")
        assertTrue(body.contains("accept_language"), "language missing: $body")
        assertTrue(body.contains("timezone_offset"), "offset missing: $body")
    }

    @Test
    fun `WL-S21 a degraded check does not hand the host a raw JDK exception`() {
        TimeZone.setDefault(SimpleTimeZone(19800000, "MyCustomTZ"))
        val server = LoopbackJsonServer(NO_MATCH_BODY).also { api = it }
        server.serveOnce()
        val latch = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink?>? = null

        performDeferredCheck(
            storage,
            FingerprintCollector(),
            ApiClient(API_KEY, server.baseUrl),
            null,
            null
        ) { r ->
            result = r
            latch.countDown()
        }

        idleMainLooperUntil(latch)

        // Previously this surfaced java.time.zone.ZoneRulesException through
        // onLink, which reads to a host as an unrelated crash inside the SDK.
        assertTrue(result!!.isSuccess, "got ${result!!.exceptionOrNull()}")
    }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val NO_MATCH_BODY = """{"matched":false}"""
        const val AWAIT_SECONDS = 5L
    }
}
