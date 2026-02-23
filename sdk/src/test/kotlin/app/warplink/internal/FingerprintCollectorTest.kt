package app.warplink.internal

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FingerprintCollectorTest {

    @Test
    fun `collectFingerprint returns success with DeviceSignals`() {
        val collector = FingerprintCollector(
            ApplicationProvider.getApplicationContext()
        )
        var result: Result<DeviceSignals>? = null
        collector.collectFingerprint { r -> result = r }
        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertNotNull(result!!.getOrNull())
    }

    @Test
    fun `acceptLanguage is non-empty`() {
        val collector = FingerprintCollector(
            ApplicationProvider.getApplicationContext()
        )
        var signals: DeviceSignals? = null
        collector.collectFingerprint { r -> signals = r.getOrNull() }
        assertTrue(signals!!.acceptLanguage.isNotEmpty())
    }

    @Test
    fun `screenWidth and screenHeight are non-negative`() {
        val collector = FingerprintCollector(
            ApplicationProvider.getApplicationContext()
        )
        var signals: DeviceSignals? = null
        collector.collectFingerprint { r -> signals = r.getOrNull() }
        assertTrue(signals!!.screenWidth >= 0)
        assertTrue(signals!!.screenHeight >= 0)
    }

    @Test
    fun `timezoneOffset matches system timezone`() {
        val collector = FingerprintCollector(
            ApplicationProvider.getApplicationContext()
        )
        var signals: DeviceSignals? = null
        collector.collectFingerprint { r -> signals = r.getOrNull() }
        val expected = -(TimeZone.getDefault().rawOffset / 60000)
        assertEquals(expected, signals!!.timezoneOffset)
    }

    @Test
    fun `userAgent matches SDK version`() {
        val collector = FingerprintCollector(
            ApplicationProvider.getApplicationContext()
        )
        var signals: DeviceSignals? = null
        collector.collectFingerprint { r -> signals = r.getOrNull() }
        assertEquals("WarpLink-Android/0.1.0", signals!!.userAgent)
    }
}
