package app.warplink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WarpLinkOptionsTest {

    @Test
    fun `WL-S14 defaults are opt-out on`() {
        val options = WarpLinkOptions()
        assertEquals("https://api.warplink.app/v1", options.apiEndpoint)
        assertFalse(options.debugLogging)
        assertTrue(options.automaticDeepLinks)
        assertTrue(options.automaticDeferredDeepLinks)
        assertEquals(emptyList(), options.linkDomains)
        assertNull(options.onLink)
    }

    @Test
    fun `WL-S08 linkDomains is optional and additive`() {
        // Every existing call site omits it and must keep the behaviour it has.
        assertEquals(emptyList(), WarpLinkOptions(debugLogging = true).linkDomains)
        assertEquals(
            listOf("links.a.com", "links.b.com"),
            WarpLinkOptions(linkDomains = listOf("links.a.com", "links.b.com")).linkDomains
        )
    }

    @Test
    fun `custom values are stored correctly`() {
        val sink: (Result<WarpLinkDeepLink>) -> Unit = {}
        val options = WarpLinkOptions(
            apiEndpoint = "https://custom.api.com/v2",
            debugLogging = true,
            automaticDeepLinks = false,
            automaticDeferredDeepLinks = false,
            onLink = sink
        )
        assertEquals("https://custom.api.com/v2", options.apiEndpoint)
        assertTrue(options.debugLogging)
        assertFalse(options.automaticDeepLinks)
        assertFalse(options.automaticDeferredDeepLinks)
        assertSame(sink, options.onLink)
    }

    @Test
    fun `onLink can be provided while auto flags stay default`() {
        var received: Result<WarpLinkDeepLink>? = null
        val options = WarpLinkOptions(onLink = { received = it })
        assertTrue(options.automaticDeepLinks)
        assertTrue(options.automaticDeferredDeepLinks)
        val link = WarpLinkDeepLink("id", "https://example.com")
        options.onLink?.invoke(Result.success(link))
        assertEquals(link, received?.getOrNull())
    }

    @Test
    fun `data class equality ignores callback identity semantics`() {
        val a = WarpLinkOptions(debugLogging = true)
        val b = WarpLinkOptions(debugLogging = true)
        assertEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = WarpLinkOptions()
        val copied = original.copy(debugLogging = true)
        assertEquals(original.apiEndpoint, copied.apiEndpoint)
        assertEquals(original.automaticDeepLinks, copied.automaticDeepLinks)
        assertEquals(original.automaticDeferredDeepLinks, copied.automaticDeferredDeepLinks)
        assertTrue(copied.debugLogging)
    }
}
