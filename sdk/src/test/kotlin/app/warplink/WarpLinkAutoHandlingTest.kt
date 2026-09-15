package app.warplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WarpLinkAutoHandlingTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val received = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()

    @Before
    fun setUp() {
        WarpLink.reset()
        // Every endpoint here is a dead port, so every request fails transiently and
        // is retried. The wait before a retry is a `postDelayed`, and nothing below
        // moves the clock, so without this the answer these tests are about would sit
        // behind a timer that never comes due. What the waits are worth is pinned in
        // BoundedRetryTest, against a fake scheduler and a fake clock.
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
        received.clear()
    }

    @After
    fun tearDown() {
        // WarpLink is an object, so the override is process-wide and outlives this
        // class inside one Gradle test JVM. reset() clears it.
        WarpLink.reset()
    }

    @Test
    fun `automaticDeferredDeepLinks false does not auto-fire`() {
        configure(automaticDeferred = false)
        watchMainLooperFor()
        assertEquals(0, received.size)
    }

    @Test
    fun `WL-S21 automaticDeferredDeepLinks true dispatches deferred failure to onLink`() {
        val latch = CountDownLatch(1)
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = true,
                onLink = { result -> received.add(result); latch.countDown() }
            )
        )

        idleMainLooperUntil(latch)

        // Offline first launch: the deferred check fails and the error reaches
        // the single onLink sink.
        assertTrue(received.isNotEmpty())
        assertIs<WarpLinkError.NetworkError>(received.first().exceptionOrNull())
    }

    @Test
    fun `WL-S04 completed deferred match is not re-dispatched to onLink on later configure`() {
        // Seed the persisted state a prior successful deferred match leaves behind.
        Storage(context).apply {
            cachedAttribution = WarpLinkDeepLink(
                linkId = "lnk_1",
                destination = "https://example.com/welcome",
                isDeferred = true
            )
            deferredCheckCompleted = true
        }

        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = true,
                onLink = { result -> received.add(result) }
            )
        )
        watchMainLooperFor()

        // A cached match must NOT be re-pushed through onLink on a later launch...
        assertEquals(0, received.size)
        // ...but stays readable on demand via attributionResult.
        assertEquals("lnk_1", WarpLink.attributionResult?.linkId)
    }

    @Test
    fun `onNewIntent dedupes the same URI within the window`() {
        configureAuto()
        val intent = intentFor("https://aplnk.to/abc123")

        WarpLink.onNewIntent(intent)
        WarpLink.onNewIntent(intent)

        idleMainLooperUntil(message = "the first dispatch never arrived") {
            received.size >= 1
        }
        // The claim is EXACTLY one, so the second half has to be watched for.
        // Returning the moment the first arrives would let a late duplicate
        // through and the suppression under test would go unverified.
        watchMainLooperFor()

        // Both calls target the same URI in quick succession -> one dispatch.
        assertEquals(1, received.size)
        assertIs<WarpLinkError.NetworkError>(received.first().exceptionOrNull())
    }

    @Test
    fun `WL-S02 onNewIntent dispatches distinct URIs separately`() {
        configureAuto()

        // One arrival, then the next. The scenario is "a second, different URL
        // arriving at an already configured SDK", and a link that overtakes one
        // still on the wire is a supersede, pinned in
        // AutoLinkHandlerSupersedeTest.
        WarpLink.onNewIntent(intentFor("https://aplnk.to/abc123"))
        idleMainLooperUntil(message = "the first URI was never dispatched") {
            received.size >= 1
        }

        WarpLink.onNewIntent(intentFor("https://aplnk.to/def456"))
        idleMainLooperUntil(message = "the second URI was never dispatched") {
            received.size >= 2
        }
        watchMainLooperFor()

        assertEquals(2, received.size)
    }

    @Test
    fun `WL-S01 cold-start link is not re-dispatched on activity recreation`() {
        configureAuto()
        val controller = Robolectric.buildActivity(
            Activity::class.java,
            intentFor("https://aplnk.to/abc123")
        )

        // Fresh launch: onActivityCreated fires with a null bundle -> one dispatch.
        // The jump below expires AutoLinkHandler's 1500 ms window explicitly, so a
        // recreation re-dispatch could only be suppressed by the savedInstanceState
        // guard, not the time window. It used to depend on a fixed drain having
        // happened to take long enough, which stated the requirement nowhere.
        //
        // Moving the clock is safe here, and only here: the dispatch above has
        // already been answered, so there is no attempt on the wire for the jump to
        // fire a watchdog against.
        controller.create()
        idleMainLooperUntil(message = "the cold-start link was never dispatched") {
            received.size >= 1
        }
        advanceBy(DEDUPE_WINDOW_MS + 500)

        // Config-change recreation: onActivityCreated fires again with a non-null
        // bundle for the same launch intent. The guard must suppress it.
        controller.recreate()
        watchMainLooperFor()

        assertEquals(1, received.size)
    }

    @Test
    fun `WL-S03 handleDeepLink shares the cold-start resolve instead of repeating it`() {
        configureAuto()
        val controller = Robolectric.buildActivity(
            Activity::class.java,
            intentFor("https://aplnk.to/abc123")
        )

        // A host that kept its 1.0.x handleDeepLink call while taking the
        // automatic default: onCreate's auto dispatch has already claimed the
        // URI and started resolving it by the time this call lands.
        controller.create()
        var manual: Result<WarpLinkDeepLink>? = null
        WarpLink.handleDeepLink(Uri.parse("https://aplnk.to/abc123")) { r ->
            manual = r
        }

        idleMainLooperUntil(message = "the shared resolve never answered both callers") {
            received.size >= 1 && manual != null
        }
        watchMainLooperFor()

        assertEquals(1, received.size)
        // One resolve, one error instance: the manual caller is answered from
        // the automatic dispatch rather than issuing a second request.
        assertSame(received.first().exceptionOrNull(), manual!!.exceptionOrNull())
    }

    @Test
    fun `WL-S09 onNewIntent ignores foreign URIs without dispatching`() {
        configureAuto()

        WarpLink.onNewIntent(intentFor("https://example.com/abc123"))

        watchMainLooperFor()

        assertEquals(0, received.size)
    }

    @Test
    fun `WL-S14 WL-S19 automaticDeepLinks false makes onNewIntent a no-op`() {
        configure(automaticDeferred = false)

        WarpLink.onNewIntent(intentFor("https://aplnk.to/abc123"))

        watchMainLooperFor()

        assertEquals(0, received.size)
    }

    @Test
    fun `WL-S19 automaticDeepLinks false does not register cold-start handling`() {
        configure(automaticDeferred = false)
        val controller = Robolectric.buildActivity(
            Activity::class.java,
            intentFor("https://aplnk.to/abc123")
        )

        controller.create()
        watchMainLooperFor()

        assertEquals(0, received.size)
    }

    @Test
    fun `configured without onLink makes onNewIntent a no-op`() {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                // Deliberately left ON: with the flag off this would pass on
                // the flag gate and stop covering the sinkless path at all.
                automaticDeepLinks = true,
                automaticDeferredDeepLinks = false
            )
        )
        // No sink, no autoHandler: must not throw.
        WarpLink.onNewIntent(intentFor("https://aplnk.to/abc123"))
        assertTrue(WarpLink.isConfigured)
    }

    private fun configure(automaticDeferred: Boolean) {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = automaticDeferred,
                onLink = { result -> received.add(result) }
            )
        )
    }

    private fun configureAuto() {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                automaticDeepLinks = true,
                automaticDeferredDeepLinks = false,
                onLink = { result -> received.add(result) }
            )
        )
    }

    private fun intentFor(url: String) =
        Intent().apply { data = Uri.parse(url) }

    private companion object {
        /** AutoLinkHandler's dedupe window, which one test has to expire. */
        const val DEDUPE_WINDOW_MS = 1_500L
    }
}
