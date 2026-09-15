package app.warplink.internal

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.SimpleTimeZone
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FingerprintCollectorTest {

    private val originalTz = TimeZone.getDefault()

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    @Test
    fun `collect returns DeviceSignals`() {
        assertNotNull(FingerprintCollector().collect())
    }

    @Test
    fun `acceptLanguage is a raw non-empty language tag`() {
        assertTrue(FingerprintCollector().collect().acceptLanguage.isNotEmpty())
    }

    @Test
    fun `an unresolvable zone drops only the zone, not the other signals`() {
        // Host code can set a process default zone whose id has no tzdb entry
        // (TimeZone.setDefault with a custom label). ZoneId.systemDefault()
        // throws on that id, and it must not take the language and the offset
        // down with it, nor cancel the attribution request.
        TimeZone.setDefault(SimpleTimeZone(19800000, "MyCustomTZ"))

        val signals = FingerprintCollector().collect()

        assertEquals("", signals.timezone)
        assertEquals(-330, signals.timezoneOffset)
        assertTrue(signals.acceptLanguage.isNotEmpty())
    }

    @Test
    fun `timezoneOffset uses DST-aware getOffset, not rawOffset`() {
        // Whichever hemisphere is on daylight saving right now: New York runs
        // March to November, Sydney October to April, so one of them is always
        // in DST and getOffset(now) always differs from rawOffset here. Pinning
        // a single zone would make this test toothless for half the year.
        val zone = assertNotNull(
            DST_ZONES
                .map { TimeZone.getTimeZone(it) }
                .firstOrNull { it.inDaylightTime(Date()) },
            "None of $DST_ZONES is observing DST right now, so this test has no " +
                "zone whose getOffset differs from its rawOffset. Add a zone that " +
                "does rather than pinning a fixed offset, which would stop this " +
                "test catching a rawOffset regression."
        )
        TimeZone.setDefault(zone)

        val signals = FingerprintCollector().collect()

        val expected = -(zone.getOffset(System.currentTimeMillis()) / 60000)
        assertEquals(expected, signals.timezoneOffset)
        // The reverted rawOffset formula drops the daylight saving hour, so this
        // is the assertion that fails if the implementation regresses.
        assertNotEquals(-(zone.rawOffset / 60000), signals.timezoneOffset)
    }

    @Test
    fun `timezoneOffset follows JS sign convention`() {
        // UTC+9 (no DST) -> JS convention is sign-inverted -> -540.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(-540, FingerprintCollector().collect().timezoneOffset)
    }

    companion object {
        private val DST_ZONES = listOf("America/New_York", "Australia/Sydney")
    }

    @Test
    fun `collects the IANA zone name, not just the offset`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        val captured = FingerprintCollector().collect()

        // The server prefers the zone name: far more entropy than the offset,
        // and it does not shift at a daylight-saving boundary.
        assertEquals("America/Toronto", captured.timezone)
    }

    @Test
    fun `resolves a legacy three-letter id to a region id`() {
        // A bare offset abbreviation carries almost no entropy and is ambiguous
        // across regions, so the reported value must be a region id.
        TimeZone.setDefault(TimeZone.getTimeZone("IST"))
        val captured = FingerprintCollector().collect()

        assertEquals("Asia/Kolkata", captured.timezone)
    }

    @Test
    fun `passes a tzdb alias through untouched for the server to normalize`() {
        // The platform keeps "Asia/Calcutta" where a browser reports
        // "Asia/Kolkata". The SDK must NOT collapse that locally: the server
        // owns the alias table, and both apps share one copy of it. Normalizing
        // here as well would put a second table in a place nothing checks.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"))
        val captured = FingerprintCollector().collect()

        assertEquals("Asia/Calcutta", captured.timezone)
    }
}
