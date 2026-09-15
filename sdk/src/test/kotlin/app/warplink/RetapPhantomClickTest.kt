package app.warplink

import android.net.Uri
import app.warplink.internal.ApiClient
import app.warplink.internal.AutoLinkHandler
import app.warplink.internal.RetrySettings
import app.warplink.internal.resolveDeepLink
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tap that is overtaken by a re-tap of the SAME link must not cost the user a
 * second click.
 *
 * The sequence, which is the one an operator on a weak uplink actually produces:
 * tap A hangs, and 1.5 seconds later, outside the dedupe window, the user taps
 * the same link again. Tap B stamps a fresh claim and disconnects A. A's
 * disconnection then runs the failure branch, and that branch used to drop the
 * claim whenever the settling URI matched, which is B's claim under B's URI. A
 * host wired both ways (automatic dispatch plus its own `handleDeepLink`, the
 * WL-S03 case) then had B's duplicate let through, and that duplicate resolved a
 * THIRD time under a NEW tap id. The tap id cannot collapse it, because the
 * second resolve mints its own: one tap, two requests, two billed clicks.
 *
 * Driven through the real [resolveDeepLink] against a scripted loopback, so the
 * disconnection is a real one on a real socket rather than a fake failure.
 */
@RunWith(RobolectricTestRunner::class)
class RetapPhantomClickTest {

    private val apiKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private val tapped: Uri = Uri.parse("https://aplnk.to/abc123")
    private val dispatched = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private val resolved = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private val stubs = mutableListOf<LoopbackJsonServer>()

    /** The dedupe window's clock, driven by the test rather than by the looper. */
    private var clock = 10_000L

    /**
     * Nothing may end an attempt on its own here: the timeouts are far longer
     * than the test, and there is no wait and no elapsed cap. The only thing
     * that can end tap A is the disconnection tap B causes, which is the whole
     * subject.
     */
    private val settings = RetrySettings(
        delaysMs = listOf(0L),
        maxJitterMs = 0L,
        totalBudgetMs = Long.MAX_VALUE,
        firstAttemptTimeoutMs = 30_000,
        retryTimeoutMs = 30_000,
    )

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `WL-S27 a re-tap of the same link is not resolved a third time`() {
        val server = stub()
        // A third answer is armed on purpose. Without it the phantom request
        // would find nobody listening, go unrecorded, and the defect would look
        // like correct behaviour.
        server.serveScript(
            LoopbackJsonServer.Answer.HANG,
            LoopbackJsonServer.Answer.RESPOND,
            LoopbackJsonServer.Answer.RESPOND,
        )
        val handler = handlerFor(server)

        handler.dispatch(tapped)
        assertTrue(server.awaitRequests(1), "tap A never reached the wire")

        // 1.6 seconds later, outside the 1.5 second window: a fresh tap on the
        // same link, which claims the handler and disconnects tap A.
        clock += 1_600
        handler.dispatch(tapped)
        assertTrue(server.awaitRequests(2), "tap B never reached the wire")
        idleMainLooperUntil(message = "tap A never settled") {
            resolved.any { it.isFailure }
        }

        // The host's other wiring, handing the SDK the same URL a second time.
        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(tapped) { manual = it }
        idleMainLooperUntil(message = "the manual caller was never answered") {
            manual != null
        }
        watchMainLooperFor()

        val ids = tapIds(server)
        assertEquals(2, ids.size, "tap A and tap B, and nothing after them")
        // From tap B onward: one request, one tap id, one delivery.
        assertEquals(1, ids.drop(1).toSet().size, "the re-tap must not mint a second tap id")
        assertEquals(1, dispatched.size, "one tap means one navigation")
        assertTrue(dispatched.first().isSuccess, "tap B's link is the one the host gets")
    }

    private fun stub() =
        LoopbackJsonServer(RESOLVE_JSON).also { stubs.add(it) }

    /**
     * The production wiring minus `configure`: the real resolver, the real
     * claim, and a clock the test owns.
     *
     * `configure` would send its own `/sdk/validate` request through the same
     * stub and race the script, which would make the request count this test
     * asserts on undefined.
     */
    private fun handlerFor(server: LoopbackJsonServer): AutoLinkHandler {
        val client = ApiClient(apiKey, server.baseUrl)
        return AutoLinkHandler(
            application = null,
            logger = null,
            isWarpLinkUri = { true },
            onLink = { dispatched.add(it) },
            resolve = { target, done ->
                resolveDeepLink(client, null, target, settings) { result ->
                    // Recorded AFTER the handler has settled this dispatch, so
                    // the test can act on "tap A is fully done" rather than
                    // guessing at a drain length.
                    done(result)
                    resolved.add(result)
                }
            },
            now = { clock }
        )
    }

    private fun tapIds(server: LoopbackJsonServer): List<String> =
        server.requestHeaders.mapNotNull { headers ->
            headers.firstOrNull { it.startsWith("X-WarpLink-Tap-Id:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        }

    private companion object {
        const val RESOLVE_JSON =
            """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":null,"android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
