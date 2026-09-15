package app.warplink.internal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeviceSignalsTest {

    @Test
    fun `fields are stored correctly`() {
        val signals = DeviceSignals(
            acceptLanguage = "en-US",
            timezoneOffset = -300
        )
        assertEquals("en-US", signals.acceptLanguage)
        assertEquals(-300, signals.timezoneOffset)
    }

    @Test
    fun `data class equality works`() {
        val a = DeviceSignals("en", -60)
        val b = DeviceSignals("en", -60)
        assertEquals(a, b)
    }

    @Test
    fun `data class inequality on different fields`() {
        val a = DeviceSignals("en", -60)
        val b = DeviceSignals("fr", -60)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val original = DeviceSignals("en", -60)
        val copied = original.copy(timezoneOffset = -120)
        assertEquals(original.acceptLanguage, copied.acceptLanguage)
        assertEquals(-120, copied.timezoneOffset)
    }

    @Test
    fun `destructuring works`() {
        val signals = DeviceSignals("en", -60)
        val (lang, tz) = signals
        assertEquals("en", lang)
        assertEquals(-60, tz)
    }
}
