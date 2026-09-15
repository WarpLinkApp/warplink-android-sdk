package app.warplink.internal

import android.net.Uri
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tapped URL's RAW query string reaches the server intact.
 *
 * The server reads `params` with `searchParams.get`, which is one transport
 * decode, and then parses the result as a query string. So the client must send
 * the raw, percent-encoded query, encoded once for transport. A decoded source
 * splits `both=x%26y` into two parameters and turns the `+` in `a%2Bb%40x.com`
 * into a space, and a double encode leaves literal `%25` sequences behind, so
 * this asserts on the bytes the client put on the wire and then reads them back
 * the way the server does.
 */
@RunWith(RobolectricTestRunner::class)
class ResolveParamsTest {

    private val stubs = mutableListOf<LoopbackJsonServer>()

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `the tapped query string is encoded exactly once on the wire`() {
        // Every character outside the unreserved set is escaped once, so the
        // whole raw query arrives as one value of one parameter.
        assertEquals(
            "domain=aplnk.to&params=note%3Da%2520b%26pct%3D100%2525%26both%3Dx%2526y" +
                "%26email%3Da%252Bb%2540x.com%26next%3D%252Fp%253Fa%253D1%2526b%253D2",
            sentQueryFor(TAPPED)
        )
    }

    @Test
    fun `the tapped query string round trips through the resolve request`() {
        val params = sentQueryFor(TAPPED).split("&")
            .map { it.split("=", limit = 2) }
            .first { it[0] == "params" }[1]

        // Decode once, exactly as the server's `searchParams.get` does. What
        // comes back is the raw query, byte for byte.
        val decoded = URLDecoder.decode(params, "UTF-8")
        assertEquals(RAW_QUERY, decoded)

        // Then parsed the way `URLSearchParams` parses it, which is what the
        // server hands to the redirect path's parameter shaping.
        val pairs = queryPairs(decoded)
        assertEquals("a b", pairs["note"])
        assertEquals("100%", pairs["pct"])
        // The whole point of sending raw: an escaped `&` stays inside one value
        // instead of becoming a second, bare parameter.
        assertEquals("x&y", pairs["both"])
        // And an escaped `+` stays a `+` instead of being read as a space.
        assertEquals("a+b@x.com", pairs["email"])
        assertEquals("/p?a=1&b=2", pairs["next"])
        assertNull(pairs["y"])
    }

    @Test
    fun `a tapped URL with no query sends no params`() {
        val query = sentQueryFor("https://aplnk.to/abc123")
        assertEquals("domain=aplnk.to", query)
    }

    @Test
    fun `the domain parameter still parses when params follows it`() {
        val query = sentQueryFor("https://aplnk.to/abc123?utm_source=email")
        assertTrue(query.startsWith("domain=aplnk.to&params="), query)
    }

    /** Resolves [tapped] against a loopback stub and returns the query string
     *  the client actually sent, still encoded. */
    private fun sentQueryFor(tapped: String): String {
        val stub = LoopbackJsonServer(RESOLVE_JSON).also { stubs.add(it) }
        stub.serveOnce()
        val done = CountDownLatch(1)
        var result: Result<WarpLinkDeepLink>? = null

        resolveDeepLink(
            ApiClient("wl_live_abcdefghijklmnopqrstuvwxyz012345", stub.baseUrl),
            null,
            Uri.parse(tapped)
        ) { result = it; done.countDown() }
        idleMainLooperUntil(done)
        result!!.getOrThrow()

        // "GET /links/resolve/abc123?domain=... HTTP/1.1"
        val line = stub.requestLines.first()
        return line.substringAfter("?").substringBefore(" ")
    }

    /** Splits a query string the way `URLSearchParams` does: on `&`, then once
     *  on `=`, then each half is decoded with a literal `+` read as a space. */
    private fun queryPairs(query: String): Map<String, String> =
        query.split("&")
            .map { it.split("=", limit = 2) }
            .associate {
                URLDecoder.decode(it[0], "UTF-8") to
                    URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8")
            }

    private companion object {
        /** A space, a `%`, an `&`, a `+`, an `@` and a nested URL: every
         *  character that breaks under a missed encode, a double encode or a
         *  decoded source. */
        const val TAPPED = "https://aplnk.to/abc123?note=a%20b&pct=100%25&both=x%26y" +
            "&email=a%2Bb%40x.com&next=%2Fp%3Fa%3D1%26b%3D2"

        /** The tapped URL's raw query, which is what one transport decode must
         *  return on the server. */
        const val RAW_QUERY = "note=a%20b&pct=100%25&both=x%26y" +
            "&email=a%2Bb%40x.com&next=%2Fp%3Fa%3D1%26b%3D2"

        const val RESOLVE_JSON = """{"id":"550e8400-e29b-41d4-a716-446655440000","slug":"abc123","domain":"aplnk.to","destination_url":"https://example.com","ios_url":null,"android_url":"myapp://android/42","custom_params":{},"created_at":"2026-01-01T00:00:00.000Z"}"""
    }
}
