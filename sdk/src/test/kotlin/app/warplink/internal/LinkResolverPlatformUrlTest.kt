package app.warplink.internal

import android.net.Uri
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals

/** Returning the iOS URL as the Android deep link kept the suite green. */
@RunWith(RobolectricTestRunner::class)
class LinkResolverPlatformUrlTest {

    private val stubs = mutableListOf<LoopbackJsonServer>()

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `the Android deep link comes from android_url, never ios_url`() {
        val stub = LoopbackJsonServer(RESOLVE_JSON).also { stubs.add(it) }
        stub.serveOnce()
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink>? = null

        resolveDeepLink(
            ApiClient("wl_live_abcdefghijklmnopqrstuvwxyz012345", stub.baseUrl),
            null,
            Uri.parse("https://aplnk.to/abc123")
        ) { result = it; done.countDown() }
        idleMainLooperUntil(done)

        assertEquals("myapp://android/42", result!!.getOrThrow().deepLinkUrl)
        // The other field the resolver maps off the same response, and the
        // documented web fallback (deepLinkUrl ?: destination). Nothing else in
        // the Android tree pins it, so a resolver that read ios_url here would
        // hand the host a scheme Android cannot open.
        assertEquals("https://example.com", result!!.getOrThrow().destination)
    }

    private companion object {
        const val RESOLVE_JSON = """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":"myapp://ios/42","android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
