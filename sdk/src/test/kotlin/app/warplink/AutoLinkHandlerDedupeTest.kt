package app.warplink

import android.net.Uri
import app.warplink.internal.AutoLinkHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the auto/manual dedupe contract: one link means one resolve, and a
 * manual caller that loses the claim is still answered.
 */
@RunWith(RobolectricTestRunner::class)
class AutoLinkHandlerDedupeTest {

    private val uri: Uri = Uri.parse("https://aplnk.to/abc123")
    private val other: Uri = Uri.parse("https://aplnk.to/def456")
    private val dispatched = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private val pendingResolves =
        mutableMapOf<String, MutableList<(Result<WarpLinkDeepLink>) -> Unit>>()
    private var resolveCount = 0

    private val handler = AutoLinkHandler(
        application = null,
        logger = null,
        isWarpLinkUri = { true },
        onLink = { result -> dispatched.add(result) },
        resolve = { target, completion ->
            resolveCount++
            pendingResolves
                .getOrPut(target.toString()) { mutableListOf() }
                .add(completion)
        }
    )

    @Test
    fun `manual caller parks on an in-flight auto dispatch`() {
        handler.dispatch(uri)
        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }

        // The cold-start dispatch owns the resolve; the manual caller must not
        // trigger a second one.
        assertEquals(1, resolveCount)

        val resolved = deepLink()
        complete(uri, resolved)

        assertEquals(1, dispatched.size)
        assertSame(resolved, dispatched.first().getOrNull())
        assertSame(resolved, manual!!.getOrNull())
    }

    @Test
    fun `manual caller is answered from an already settled dispatch`() {
        handler.dispatch(uri)
        val resolved = deepLink()
        complete(uri, resolved)

        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }

        assertEquals(1, resolveCount)
        assertSame(resolved, manual!!.getOrNull())
    }

    @Test
    fun `auto dispatch is suppressed after a manual caller claimed the URI`() {
        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }
        handler.dispatch(uri)

        assertEquals(1, resolveCount)

        val resolved = deepLink()
        complete(uri, resolved)

        // The manual caller owns this URI, so onLink stays silent.
        assertTrue(dispatched.isEmpty())
        assertSame(resolved, manual!!.getOrNull())
    }

    @Test
    fun `a distinct URI is resolved on its own`() {
        handler.dispatch(uri)
        // Settled before the second arrival. Two claims live at once is a
        // supersede now, and AutoLinkHandlerSupersedeTest is where that is
        // pinned; this one is about a distinct URI not being deduped.
        complete(uri, deepLink())

        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(other) { result -> manual = result }

        assertEquals(2, resolveCount)

        val secondResult = deepLink("lnk_2")
        complete(other, secondResult)

        assertEquals(1, dispatched.size)
        assertSame(secondResult, manual!!.getOrNull())
    }

    // conformance: WL-S03
    @Test
    fun `a successful resolve keeps its claim, so a duplicate is still suppressed`() {
        handler.dispatch(uri)
        complete(uri, deepLink())
        // The host's other wiring, inside the window, after the first resolve
        // has already succeeded.
        handler.dispatch(uri)

        // Dropping the claim on a stale settle, or on any settle, would show up
        // here as a second resolve and a second billed click for one link.
        assertEquals(1, resolveCount, "a settled success must still suppress its duplicate")
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `a failed resolve is not replayed to a later caller`() {
        handler.dispatch(uri)
        completeWith(uri, Result.failure(WarpLinkError.LinkNotFound))

        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }

        // A failure is not a duplicate to suppress: the retry must really
        // re-resolve rather than be handed the cached failure back.
        assertEquals(2, resolveCount)
        assertNull(manual)

        val resolved = deepLink()
        complete(uri, resolved)
        assertSame(resolved, manual!!.getOrNull())
    }

    @Test
    fun `a throwing onLink still answers a parked manual caller`() {
        val throwing = AutoLinkHandler(
            application = null,
            logger = null,
            isWarpLinkUri = { true },
            onLink = { throw IllegalStateException("host consumer blew up") },
            resolve = { target, completion ->
                pendingResolves
                    .getOrPut(target.toString()) { mutableListOf() }
                    .add(completion)
            }
        )
        throwing.dispatch(uri)
        var manual: Result<WarpLinkDeepLink>? = null
        throwing.dispatchManual(uri) { result -> manual = result }

        val resolved = deepLink()
        assertFailsWith<IllegalStateException> { complete(uri, resolved) }

        // The parked caller is settled before the consumer runs, so a throwing
        // consumer can no longer orphan it.
        assertSame(resolved, manual!!.getOrNull())
    }

    private fun complete(target: Uri, deepLink: WarpLinkDeepLink) =
        completeWith(target, Result.success(deepLink))

    private fun completeWith(target: Uri, result: Result<WarpLinkDeepLink>) {
        pendingResolves.remove(target.toString())
            .orEmpty()
            .forEach { it(result) }
    }

    private fun deepLink(linkId: String = "lnk_1") = WarpLinkDeepLink(
        linkId = linkId,
        destination = "https://example.com/$linkId"
    )

    // conformance: WL-S05
    @Test
    fun `a deferred no-match is not dispatched to onLink`() {
        // A no-match is the normal organic install. Dispatching it would hand
        // the host an empty event on most launches, and the three SDKs have to
        // agree on staying silent. The suppression lives in dispatchDeferred,
        // so drive a real success-with-null through it rather than asserting on
        // the branch from the outside.
        handler.dispatchDeferred(Result.success(null))

        assertTrue(dispatched.isEmpty(), "a no-match must not reach onLink")
    }

    // conformance: WL-S05
    @Test
    fun `a deferred match is dispatched, so silence is not the only behaviour`() {
        // The control for the test above: without it, a dispatchDeferred that
        // dropped EVERYTHING would still look correct.
        val link = WarpLinkDeepLink(
            linkId = "link-1",
            destination = "https://example.com",
            isDeferred = true
        )

        handler.dispatchDeferred(Result.success(link))

        assertEquals(1, dispatched.size)
        assertEquals("link-1", dispatched[0].getOrNull()?.linkId)
    }

    // conformance: WL-S05
    @Test
    fun `a deferred failure is still dispatched`() {
        // Silence is for "nothing matched", not for "the check broke". A host
        // that never hears about a failure cannot retry or report it.
        val boom = WarpLinkError.NetworkError(java.io.IOException("offline"))

        handler.dispatchDeferred(Result.failure(boom))

        assertEquals(1, dispatched.size)
        assertTrue(dispatched[0].isFailure)
    }
}
