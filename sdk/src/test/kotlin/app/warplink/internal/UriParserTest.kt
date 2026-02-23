package app.warplink.internal

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UriParserTest {

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
    fun `nested path returns first segment as slug`() {
        val uri = Uri.parse("https://aplnk.to/abc123/extra/path")
        assertEquals("abc123", UriParser.extractSlug(uri))
    }

    @Test
    fun `extractDomain falls back to default for no host`() {
        val uri = Uri.parse("/abc123")
        assertEquals("aplnk.to", UriParser.extractDomain(uri))
    }
}
