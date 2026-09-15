package app.warplink

import android.net.Uri
import app.warplink.internal.AutoLinkHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A tap now lives long enough for the next one to arrive while it is still
 * retrying, so the handler has to decide what an overtaken tap does.
 *
 * The split: it stays silent to the host, because one tap means one navigation,
 * but everyone parked on its claim is still answered. Nothing may be stranded.
 */
@RunWith(RobolectricTestRunner::class)
class AutoLinkHandlerSupersedeTest {

    private val uri: Uri = Uri.parse("https://aplnk.to/abc123")
    private val newer: Uri = Uri.parse("https://aplnk.to/def456")
    private val dispatched = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private val pending =
        mutableMapOf<String, MutableList<(Result<WarpLinkDeepLink>) -> Unit>>()

    private val handler = AutoLinkHandler(
        application = null,
        logger = null,
        isWarpLinkUri = { true },
        onLink = { result -> dispatched.add(result) },
        resolve = { target, completion ->
            pending.getOrPut(target.toString()) { mutableListOf() }.add(completion)
        }
    )

    @Test
    fun `a superseded auto tap never reaches the host`() {
        handler.dispatch(uri)
        // A newer tap claims the handler while the first is still resolving.
        handler.dispatch(newer)

        complete(uri, deepLink("lnk_old"))

        assertTrue(dispatched.isEmpty(), "the overtaken tap must not navigate the host")
    }

    @Test
    fun `the newer tap is the one that does reach the host`() {
        // The control. Without it a dispatch that silenced EVERYTHING would
        // look identical to the test above.
        handler.dispatch(uri)
        handler.dispatch(newer)

        complete(uri, deepLink("lnk_old"))
        val current = deepLink("lnk_new")
        complete(newer, current)

        assertEquals(1, dispatched.size)
        assertSame(current, dispatched.first().getOrNull())
    }

    @Test
    fun `a manual caller parked on a superseded tap is still answered`() {
        handler.dispatch(uri)
        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }
        handler.dispatch(newer)

        val resolved = deepLink()
        complete(uri, resolved)

        // Silent to the host, but the caller parked on this claim is owed an
        // answer: only settle drains `waiting`, so a run that returned without
        // it would hang that caller for the life of the process.
        assertTrue(dispatched.isEmpty())
        assertSame(resolved, manual!!.getOrNull())
    }

    @Test
    fun `a manual caller that owns a superseded tap is still answered`() {
        var manual: Result<WarpLinkDeepLink>? = null
        handler.dispatchManual(uri) { result -> manual = result }
        handler.dispatch(newer)

        val resolved = deepLink()
        complete(uri, resolved)

        // dispatchManual has no supersede check at all, deliberately: the
        // caller asked about one URL inline and is owed its answer whatever a
        // later tap does.
        assertSame(resolved, manual!!.getOrNull())
    }

    // conformance: WL-S21
    @Test
    fun `a failure that was never superseded still reaches the host`() {
        handler.dispatch(uri)
        val boom = WarpLinkError.NetworkError(IOException("offline"))

        completeWith(uri, Result.failure(boom))

        // The supersede flag has to be read BEFORE settle. settle drops the
        // claim on a failure (lastUri = null), so a flag read afterwards would
        // report every failed resolve as superseded and WL-S21 would stop
        // reaching the host entirely.
        assertEquals(1, dispatched.size)
        assertSame(boom, dispatched.first().exceptionOrNull())
    }

    private fun complete(target: Uri, deepLink: WarpLinkDeepLink) =
        completeWith(target, Result.success(deepLink))

    private fun completeWith(target: Uri, result: Result<WarpLinkDeepLink>) {
        pending.remove(target.toString()).orEmpty().forEach { it(result) }
    }

    private fun deepLink(linkId: String = "lnk_1") = WarpLinkDeepLink(
        linkId = linkId,
        destination = "https://example.com/$linkId"
    )
}
