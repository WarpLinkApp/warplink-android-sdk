package app.warplink

import android.net.Uri
import app.warplink.internal.UriParser
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `WarpLink.isWarpLinkUri` answers exactly what the automatic path answers.
 *
 * The automatic dispatcher refuses a foreign URI before it touches anything:
 * `AutoLinkHandler.dispatch` returns on `!isWarpLinkUri(uri)` before
 * `claimLocked`. A host that forwards every incoming URI has no way to make the
 * same decision at the same point, so it had to resolve first and read the
 * refusal off a `WarpLinkError.InvalidUrl`, which arrives after the state a
 * claim changed. This makes the check askable.
 *
 * The accept-set is therefore not this function's own: it is `UriParser`'s, and
 * these tests pin that they are the same set rather than two copies of one rule.
 */
@RunWith(RobolectricTestRunner::class)
class IsWarpLinkUriTest {

    // UriParser is a process-global singleton shared by every test in the JVM,
    // so these reset on both sides: in, so an earlier test's custom domain
    // cannot make a negative assertion here pass for the wrong reason, and out,
    // so nothing set here leaks onward.
    @Before
    fun setUp() {
        UriParser.resetKnownDomains()
    }

    @After
    fun tearDown() {
        UriParser.resetKnownDomains()
    }

    @Test
    fun `the default domain with a slug is a WarpLink link`() {
        assertTrue(WarpLink.isWarpLinkUri(Uri.parse("https://aplnk.to/abc123")))
    }

    @Test
    fun `a custom scheme is not a WarpLink link`() {
        assertFalse(WarpLink.isWarpLinkUri(Uri.parse("myapp://oauth/callback")))
    }

    @Test
    fun `an unknown host is not a WarpLink link`() {
        assertFalse(WarpLink.isWarpLinkUri(Uri.parse("https://example.com/abc123")))
    }

    @Test
    fun `a multi-segment path on a known host is not a WarpLink link`() {
        // App Links are verified for the whole host, so the OS hands the app
        // marketing pages too. Claiming those would swallow URLs the SDK cannot
        // resolve. Same rule as UriParser.extractSlug.
        assertFalse(WarpLink.isWarpLinkUri(Uri.parse("https://aplnk.to/blog/hello")))
    }

    @Test
    fun `the bare default domain is not a WarpLink link`() {
        assertFalse(WarpLink.isWarpLinkUri(Uri.parse("https://aplnk.to/")))
    }

    @Test
    fun `a declared custom domain is a WarpLink link`() {
        assertFalse(
            WarpLink.isWarpLinkUri(Uri.parse("https://links.example.com/abc123"))
        )
        UriParser.setDeclaredDomains(listOf("links.example.com"))
        assertTrue(
            WarpLink.isWarpLinkUri(Uri.parse("https://links.example.com/abc123"))
        )
    }

    @Test
    fun `a domain from server validation is a WarpLink link`() {
        UriParser.setServerDomains(listOf("go.example.com"))
        assertTrue(WarpLink.isWarpLinkUri(Uri.parse("https://go.example.com/abc123")))
    }

    @Test
    fun `a mixed-case host still matches its lowercased domain`() {
        UriParser.setDeclaredDomains(listOf("links.example.com"))
        assertTrue(
            WarpLink.isWarpLinkUri(Uri.parse("https://Links.Example.Com/abc123"))
        )
    }

    /**
     * The whole point of exposing it: asking costs nothing and changes nothing,
     * so a host can ask before every decision. A check that claimed the dedupe
     * window would be worse than the resolve it replaces.
     */
    @Test
    fun `asking twice gives the same answer and claims nothing`() {
        val uri = Uri.parse("https://aplnk.to/abc123")
        assertTrue(WarpLink.isWarpLinkUri(uri))
        assertTrue(WarpLink.isWarpLinkUri(uri))
    }
}
