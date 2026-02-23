package app.warplink.internal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceSignalsTest {

    @Test
    fun `fields are stored correctly`() {
        val signals = DeviceSignals(
            acceptLanguage = "en-US",
            screenWidth = 1080,
            screenHeight = 1920,
            timezoneOffset = -300,
            userAgent = "WarpLink-Android/0.1.0"
        )
        assertEquals("en-US", signals.acceptLanguage)
        assertEquals(1080, signals.screenWidth)
        assertEquals(1920, signals.screenHeight)
        assertEquals(-300, signals.timezoneOffset)
        assertEquals("WarpLink-Android/0.1.0", signals.userAgent)
    }

    @Test
    fun `data class equality works`() {
        val a = DeviceSignals("en", 100, 200, -60, "ua")
        val b = DeviceSignals("en", 100, 200, -60, "ua")
        assertEquals(a, b)
    }

    @Test
    fun `data class inequality on different fields`() {
        val a = DeviceSignals("en", 100, 200, -60, "ua")
        val b = DeviceSignals("fr", 100, 200, -60, "ua")
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = DeviceSignals("en", 100, 200, -60, "ua")
        val copied = original.copy(screenWidth = 500)
        assertEquals(original.acceptLanguage, copied.acceptLanguage)
        assertEquals(500, copied.screenWidth)
        assertEquals(original.screenHeight, copied.screenHeight)
        assertEquals(original.timezoneOffset, copied.timezoneOffset)
        assertEquals(original.userAgent, copied.userAgent)
    }

    @Test
    fun `destructuring works`() {
        val signals = DeviceSignals("en", 100, 200, -60, "ua")
        val (lang, width, height, tz, ua) = signals
        assertEquals("en", lang)
        assertEquals(100, width)
        assertEquals(200, height)
        assertEquals(-60, tz)
        assertEquals("ua", ua)
    }
}
