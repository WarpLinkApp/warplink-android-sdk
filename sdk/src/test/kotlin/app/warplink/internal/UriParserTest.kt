package app.warplink.internal

import android.net.Uri
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UriParserTest {

    @After
    fun tearDown() {
        // UriParser is a process-global singleton; reset so custom domains set
        // here do not leak into other tests.
        UriParser.resetKnownDomains()
    }

    @Test
    fun `custom domain from validate is recognized after setServerDomains`() {
        assertFalse(
            UriParser.isWarpLinkUri(Uri.parse("https://links.example.com/abc"))
        )
        UriParser.setServerDomains(listOf("links.example.com"))
        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://links.example.com/abc"))
        )
    }

    @Test
    fun `default domain stays recognized after custom domains are set`() {
        UriParser.setServerDomains(listOf("links.example.com"))
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://aplnk.to/abc")))
    }

    @Test
    fun `resetKnownDomains drops every source`() {
        UriParser.setServerDomains(listOf("links.server.com"))
        UriParser.setDeclaredDomains(listOf("links.declared.com"))
        UriParser.resetKnownDomains()
        assertFalse(
            UriParser.isWarpLinkUri(Uri.parse("https://links.server.com/abc"))
        )
        assertFalse(
            UriParser.isWarpLinkUri(Uri.parse("https://links.declared.com/abc"))
        )
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://aplnk.to/abc")))
    }

    @Test
    fun `WL-S08 a server response does not erase a locally declared domain`() {
        // The two sources are unioned, never overwritten. A validate response
        // that does not name the declared domain (a stale org list, a domain
        // still verifying) must not take away the only source available on a
        // first launch.
        UriParser.setDeclaredDomains(listOf("links.declared.com"))
        UriParser.setServerDomains(listOf("links.server.com"))

        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://links.declared.com/abc"))
        )
        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://links.server.com/abc"))
        )
    }

    @Test
    fun `a locally declared domain does not hide a server domain`() {
        // The other direction: an org adding a domain after this build shipped
        // still resolves, so declaring locally is never a downgrade.
        UriParser.setServerDomains(listOf("links.server.com"))
        UriParser.setDeclaredDomains(listOf("links.declared.com"))

        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://links.server.com/abc"))
        )
    }

    @Test
    fun `declared domains are normalized before matching`() {
        UriParser.setDeclaredDomains(listOf(" HTTPS://Links.Example.com/ "))
        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://links.example.com/abc"))
        )
    }

    @Test
    fun `an uppercase host still matches a known domain`() {
        UriParser.setDeclaredDomains(listOf("links.example.com"))
        assertTrue(
            UriParser.isWarpLinkUri(Uri.parse("https://LINKS.Example.COM/abc"))
        )
        assertEquals(
            "links.example.com",
            UriParser.extractDomain(Uri.parse("https://LINKS.Example.COM/abc"))
        )
    }

    @Test
    fun `valid WarpLink URI returns slug and domain`() {
        val uri = Uri.parse("https://aplnk.to/abc123")
        assertTrue(UriParser.isWarpLinkUri(uri))
        assertEquals("abc123", UriParser.extractSlug(uri))
        assertEquals("aplnk.to", UriParser.extractDomain(uri))
    }

    @Test
    fun `trailing slash is handled`() {
        val uri = Uri.parse("https://aplnk.to/abc123/")
        assertEquals("abc123", UriParser.extractSlug(uri))
    }

    @Test
    fun `query params are stripped`() {
        val uri = Uri.parse("https://aplnk.to/abc123?utm=test")
        assertEquals("abc123", UriParser.extractSlug(uri))
    }

    @Test
    fun `fragment is stripped`() {
        val uri = Uri.parse("https://aplnk.to/abc123#section")
        assertEquals("abc123", UriParser.extractSlug(uri))
    }

    @Test
    fun `root path with trailing slash returns null slug`() {
        val uri = Uri.parse("https://aplnk.to/")
        assertNull(UriParser.extractSlug(uri))
    }

    @Test
    fun `bare domain returns null slug`() {
        val uri = Uri.parse("https://aplnk.to")
        assertNull(UriParser.extractSlug(uri))
    }

    @Test
    fun `unknown domain returns isWarpLink false`() {
        val uri = Uri.parse("https://example.com/abc123")
        assertFalse(UriParser.isWarpLinkUri(uri))
    }

    @Test
    fun `empty URI returns isWarpLink false`() {
        val uri = Uri.parse("")
        assertFalse(UriParser.isWarpLinkUri(uri))
    }

    @Test
    fun `URI without host returns isWarpLink false`() {
        val uri = Uri.parse("/abc123")
        assertFalse(UriParser.isWarpLinkUri(uri))
    }

    @Test
    fun `WL-S10 nested path returns null slug`() {
        // A slug is always a single path segment, so /abc123/extra/path is not a
        // WarpLink link. Returning the first segment would resolve an unrelated
        // link that happens to be named "abc123".
        val uri = Uri.parse("https://aplnk.to/abc123/extra/path")
        assertNull(UriParser.extractSlug(uri))
    }

    @Test
    fun `nested path with trailing slash returns null slug`() {
        val uri = Uri.parse("https://aplnk.to/abc123/extra/")
        assertNull(UriParser.extractSlug(uri))
    }

    @Test
    fun `nested path returns isWarpLink false on a known domain`() {
        // App Links are registered for the whole host, so the OS hands the app
        // every URL on the domain. Only resolvable ones may be claimed.
        val uri = Uri.parse("https://aplnk.to/abc123/extra/path")
        assertFalse(UriParser.isWarpLinkUri(uri))
    }

    @Test
    fun `percent encoded slash returns null slug`() {
        // pathSegments decodes each segment, so %2F survives inside one segment.
        // The slug is interpolated into the resolve request URL, so reject it.
        val uri = Uri.parse("https://aplnk.to/a%2Fb")
        assertNull(UriParser.extractSlug(uri))
    }

    @Test
    fun `root path returns isWarpLink false on a known domain`() {
        assertFalse(UriParser.isWarpLinkUri(Uri.parse("https://aplnk.to/")))
    }

    @Test
    fun `extractDomain falls back to default for no host`() {
        val uri = Uri.parse("/abc123")
        assertEquals("aplnk.to", UriParser.extractDomain(uri))
    }
}
