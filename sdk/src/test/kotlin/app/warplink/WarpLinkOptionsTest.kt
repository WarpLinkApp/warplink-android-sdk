package app.warplink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WarpLinkOptionsTest {

    @Test
    fun `default values are correct`() {
        val options = WarpLinkOptions()
        assertEquals("https://api.warplink.app/v1", options.apiEndpoint)
        assertFalse(options.debugLogging)
        assertEquals(72, options.matchWindowHours)
    }

    @Test
    fun `custom values are stored correctly`() {
        val options = WarpLinkOptions(
            apiEndpoint = "https://custom.api.com/v2",
            debugLogging = true,
            matchWindowHours = 48
        )
        assertEquals("https://custom.api.com/v2", options.apiEndpoint)
        assertEquals(true, options.debugLogging)
        assertEquals(48, options.matchWindowHours)
    }

    @Test
    fun `data class equality works`() {
        val a = WarpLinkOptions(debugLogging = true)
        val b = WarpLinkOptions(debugLogging = true)
        assertEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = WarpLinkOptions()
        val copied = original.copy(debugLogging = true)
        assertEquals(original.apiEndpoint, copied.apiEndpoint)
        assertEquals(original.matchWindowHours, copied.matchWindowHours)
        assertEquals(true, copied.debugLogging)
    }
}
