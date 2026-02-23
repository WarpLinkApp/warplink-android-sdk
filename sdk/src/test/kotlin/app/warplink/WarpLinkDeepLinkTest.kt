package app.warplink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarpLinkDeepLinkTest {

    @Test
    fun `required fields are stored`() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link_123",
            destination = "https://example.com"
        )
        assertEquals("link_123", deepLink.linkId)
        assertEquals("https://example.com", deepLink.destination)
    }

    @Test
    fun `optional fields default to null or empty`() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link_123",
            destination = "https://example.com"
        )
        assertNull(deepLink.deepLinkUrl)
        assertEquals(emptyMap(), deepLink.customParams)
        assertFalse(deepLink.isDeferred)
        assertNull(deepLink.matchType)
        assertNull(deepLink.matchConfidence)
    }

    @Test
    fun `all fields populated`() {
        val params = mapOf<String, Any>("key" to "value", "count" to 42)
        val deepLink = WarpLinkDeepLink(
            linkId = "link_456",
            destination = "https://example.com/page",
            deepLinkUrl = "myapp://path",
            customParams = params,
            isDeferred = true,
            matchType = MatchType.PROBABILISTIC,
            matchConfidence = 0.85
        )
        assertEquals("link_456", deepLink.linkId)
        assertEquals("https://example.com/page", deepLink.destination)
        assertEquals("myapp://path", deepLink.deepLinkUrl)
        assertEquals(params, deepLink.customParams)
        assertTrue(deepLink.isDeferred)
        assertEquals(MatchType.PROBABILISTIC, deepLink.matchType)
        assertEquals(0.85, deepLink.matchConfidence)
    }
}
