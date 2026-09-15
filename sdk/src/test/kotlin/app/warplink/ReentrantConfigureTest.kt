package app.warplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `configure` runs host code synchronously before it finishes: a caller parked
 * on a deferred check that the reconfigure abandons is answered inline. A host
 * that reconfigures from inside that answer re-enters `configure`, so the outer
 * call finishes against state the inner call already replaced.
 *
 * That makes the cold-start registration hazard reachable on one thread through
 * the public API, with no concurrency at all.
 */
@RunWith(RobolectricTestRunner::class)
class ReentrantConfigureTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val received = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private var server: LoopbackJsonServer? = null
    private val gate = CountDownLatch(1)

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
        received.clear()
    }

    @After
    fun tearDown() {
        gate.countDown()
        server?.close()
        WarpLink.reset()
    }

    @Test
    fun `WL-S23 a reconfigure from an abandoned deferred callback double-registers cold start`() {
        val stub = LoopbackJsonServer("""{"matched":false}""").also { server = it }
        stub.serveOnce(gate)

        // 1. A deferred check that is genuinely in flight: the stub has read the
        //    request and is holding the response until the gate opens.
        configure(auto = false, deferred = false, sink = false)
        WarpLink.checkDeferredDeepLink { }
        assertTrue(stub.requestReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS))

        // 2. A second caller parks behind the in-flight check.
        WarpLink.checkDeferredDeepLink {
            // 4. Answered inline by step 3's reconfigure. Reconfiguring here
            //    installs a new handler and registers its cold-start handling,
            //    all before step 3's own startAutomaticHandling has run.
            if (WarpLink.isConfigured) configure(auto = true, deferred = false, sink = true)
        }

        // 3. Abandons the parked caller, which re-enters configure above. When
        //    control returns, this call registers cold start a second time on
        //    the handler the inner call installed.
        configure(auto = false, deferred = false, sink = false)

        // 5. Retire everything. One unregister cannot reach two registrations.
        received.clear()
        WarpLink.reset()

        Robolectric.buildActivity(
            Activity::class.java,
            Intent().apply { data = Uri.parse("https://aplnk.to/abc123") }
        ).create()

        assertEquals(0, received.size)
    }

    /**
     * Control for the scenario above. Identical, except the answered caller does
     * NOT reconfigure. One registration, one unregister, nothing left behind.
     * If this ever fails, the scenario above is not proving what it claims.
     */
    @Test
    fun `without the re-entrant reconfigure one unregister is enough`() {
        val stub = LoopbackJsonServer("""{"matched":false}""").also { server = it }
        stub.serveOnce(gate)

        configure(auto = false, deferred = false, sink = false)
        WarpLink.checkDeferredDeepLink { }
        assertTrue(stub.requestReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS))

        WarpLink.checkDeferredDeepLink { }

        configure(auto = true, deferred = false, sink = true)

        received.clear()
        WarpLink.reset()

        Robolectric.buildActivity(
            Activity::class.java,
            Intent().apply { data = Uri.parse("https://aplnk.to/abc123") }
        ).create()

        assertEquals(0, received.size)
    }

    private fun configure(auto: Boolean, deferred: Boolean, sink: Boolean) {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = server?.baseUrl ?: "http://localhost:1",
                automaticDeepLinks = auto,
                automaticDeferredDeepLinks = deferred,
                onLink = if (sink) ({ result -> received.add(result) }) else null
            )
        )
    }

    private companion object {
        const val AWAIT_SECONDS = 5L
    }
}
