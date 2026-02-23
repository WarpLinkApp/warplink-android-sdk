package app.warplink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WarpLinkErrorTest {

    @Test
    fun `NotConfigured has correct message`() {
        val error = WarpLinkError.NotConfigured
        assertTrue(error.message!!.contains("not configured"))
    }

    @Test
    fun `InvalidApiKeyFormat has correct message`() {
        val error = WarpLinkError.InvalidApiKeyFormat
        assertTrue(error.message!!.contains("Invalid API key format"))
    }

    @Test
    fun `InvalidApiKey has correct message`() {
        val error = WarpLinkError.InvalidApiKey
        assertTrue(error.message!!.contains("invalid or has been revoked"))
    }

    @Test
    fun `InvalidUrl has correct message`() {
        val error = WarpLinkError.InvalidUrl
        assertTrue(error.message!!.contains("not a valid"))
    }

    @Test
    fun `LinkNotFound has correct message`() {
        val error = WarpLinkError.LinkNotFound
        assertTrue(error.message!!.contains("not found"))
    }

    @Test
    fun `singleton errors are referentially equal`() {
        assertSame(WarpLinkError.NotConfigured, WarpLinkError.NotConfigured)
        assertSame(WarpLinkError.InvalidApiKeyFormat, WarpLinkError.InvalidApiKeyFormat)
        assertSame(WarpLinkError.InvalidApiKey, WarpLinkError.InvalidApiKey)
        assertSame(WarpLinkError.InvalidUrl, WarpLinkError.InvalidUrl)
        assertSame(WarpLinkError.LinkNotFound, WarpLinkError.LinkNotFound)
    }

    @Test
    fun `NetworkError wraps cause`() {
        val cause = RuntimeException("connection refused")
        val error = WarpLinkError.NetworkError(cause)
        assertIs<WarpLinkError>(error)
        assertEquals(cause, error.cause)
        assertTrue(error.message!!.contains("connection refused"))
    }

    @Test
    fun `ServerError stores statusCode and message`() {
        val error = WarpLinkError.ServerError(500, "Internal Server Error")
        assertEquals(500, error.statusCode)
        assertEquals("Internal Server Error", error.message)
    }

    @Test
    fun `DecodingError wraps cause`() {
        val cause = IllegalArgumentException("unexpected token")
        val error = WarpLinkError.DecodingError(cause)
        assertIs<WarpLinkError>(error)
        assertEquals(cause, error.cause)
        assertTrue(error.message!!.contains("unexpected token"))
    }

    @Test
    fun `all errors are instances of Exception`() {
        assertIs<Exception>(WarpLinkError.NotConfigured)
        assertIs<Exception>(WarpLinkError.InvalidApiKeyFormat)
        assertIs<Exception>(WarpLinkError.InvalidApiKey)
        assertIs<Exception>(WarpLinkError.NetworkError(RuntimeException()))
        assertIs<Exception>(WarpLinkError.ServerError(400, "Bad Request"))
        assertIs<Exception>(WarpLinkError.InvalidUrl)
        assertIs<Exception>(WarpLinkError.LinkNotFound)
        assertIs<Exception>(WarpLinkError.DecodingError(RuntimeException()))
    }

    @Test
    fun `MatchType enum has both values`() {
        val values = MatchType.values()
        assertEquals(2, values.size)
        assertNotNull(MatchType.valueOf("DETERMINISTIC"))
        assertNotNull(MatchType.valueOf("PROBABILISTIC"))
    }
}
