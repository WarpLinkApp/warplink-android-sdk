package app.warplink.internal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GOLDEN VECTORS. This exact table is asserted by the iOS SDK and by the Android
 * SDK, row for row. A developer declares the same domain in both apps, from the
 * same dashboard, usually by pasting the same string, so the two platforms have to
 * reduce it identically. A divergence has to fail a test rather than surface as
 * "the link works on Android but not on iOS".
 *
 * The table is duplicated per platform on purpose: there is no build step that
 * could share it across two package managers. The first version of these two
 * normalizers drifted on five inputs while BOTH files claimed the other asserted
 * the same table, and the two tables did not share a single row. Keep them
 * identical. Change a rule, change both, add the row to both.
 */
private val GOLDEN_VECTORS: List<Pair<String, String?>> = listOf(
    // Already a bare host: unchanged.
    "links.example.com" to "links.example.com",
    "aplnk.to" to "aplnk.to",
    // Whitespace, from a copied cell or a wrapped manifest value.
    "  links.example.com  " to "links.example.com",
    "\tlinks.example.com\n" to "links.example.com",
    // Hosts are case insensitive, so a declaration is folded down.
    "LINKS.EXAMPLE.COM" to "links.example.com",
    "Links.Example.Com" to "links.example.com",
    // A pasted URL is reduced to its host.
    "https://links.example.com" to "links.example.com",
    "https://links.example.com/" to "links.example.com",
    "http://links.example.com/a/b" to "links.example.com",
    "HTTPS://Links.Example.Com/" to "links.example.com",
    "https://links.example.com/abc123?utm_source=x#frag" to "links.example.com",
    "https://links.example.com?a=b" to "links.example.com",
    "links.example.com/abc123" to "links.example.com",
    "links.example.com#frag" to "links.example.com",
    // A protocol relative href, which is what a copied <a> tag often gives.
    "//links.example.com" to "links.example.com",
    "//links.example.com/abc" to "links.example.com",
    // Credentials are dropped. Without this the host becomes `user`.
    "user@links.example.com" to "links.example.com",
    "https://user:pw@links.example.com/" to "links.example.com",
    // A port is dropped: the host a link arrives with never carries one.
    "https://links.example.com:8443/" to "links.example.com",
    "links.example.com:8443" to "links.example.com",
    // Only a real port. A non numeric suffix is left alone rather than truncated,
    // and an IPv6 literal keeps its colons.
    "links.example.com:notaport" to "links.example.com:notaport",
    "[::1]:8443" to "[::1]",
    // `www.` is a real and different host, so it survives.
    "www.example.com" to "www.example.com",
    "https://www.example.com/" to "www.example.com",
    // Nothing that names a host.
    "" to null,
    "   " to null,
    "https://" to null,
    "/" to null,
    "/abc123" to null,
    "//" to null,
    // Invisible characters a paste can carry: stripped identically on both
    // platforms, from an explicit list, because the platform trims disagree.
    "\u200Blinks.example.com" to "links.example.com",
    "links.example.com\u200B" to "links.example.com",
    "\u00A0links.example.com\u00A0" to "links.example.com",
    "\uFEFFlinks.example.com" to "links.example.com",
    "\u2060links.example.com" to "links.example.com",
    "links.example.com\u200D" to "links.example.com",
    // A control character is not in the list, on either side, so it stays.
    "\u001Flinks.example.com" to "\u001Flinks.example.com",
)

class LinkDomainNormalizationTest {

    @Test
    fun `WL-S08 golden vectors normalize identically on every platform`() {
        for ((input, expected) in GOLDEN_VECTORS) {
            assertEquals(
                expected,
                normalizeLinkDomain(input),
                "normalizeLinkDomain(\"$input\")"
            )
        }
    }

    @Test
    fun `port is dropped because a URI host never carries one`() {
        assertEquals("links.a.com", normalizeLinkDomain("links.a.com:8080"))
        assertEquals("links.a.com", normalizeLinkDomain("https://links.a.com:443/x"))
    }

    @Test
    fun `a value that is not a URL is left intact rather than truncated`() {
        // Only a numeric tail is treated as a port, so a stray colon does not
        // silently eat half of whatever the developer pasted.
        assertEquals("links.a.com:notaport", normalizeLinkDomain("links.a.com:notaport"))
    }

    @Test
    fun `protocol relative and userinfo forms reduce to the host`() {
        assertEquals("links.a.com", normalizeLinkDomain("//links.a.com/abc"))
        assertEquals("links.a.com", normalizeLinkDomain("https://user:pw@links.a.com/abc"))
    }

    @Test
    fun `normalizeLinkDomains drops empties and de-duplicates`() {
        val result = normalizeLinkDomains(
            listOf("links.a.com", "", "  ", "https://links.a.com/", "LINKS.B.COM")
        )
        assertEquals(setOf("links.a.com", "links.b.com"), result)
    }

    @Test
    fun `normalizeLinkDomains preserves declaration order`() {
        // A set, but an ordered one, so a log line reads back the way the
        // developer wrote it.
        assertEquals(
            listOf("links.b.com", "links.a.com"),
            normalizeLinkDomains(listOf("links.b.com", "links.a.com")).toList()
        )
    }

    @Test
    fun `an empty declaration list yields an empty set`() {
        assertNull(normalizeLinkDomain("#"))
        assertEquals(emptySet(), normalizeLinkDomains(emptyList()))
    }
}
