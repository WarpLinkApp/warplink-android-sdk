package app.warplink.internal

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the POST /attribution/match wire body to the locked contract:
 * the enriched raw-signals path carries only accept_language + timezone_offset
 * (User-Agent and screen dimensions are dropped from the fingerprint), and the
 * deterministic referrer path still sends the required fingerprint_version.
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientAttributionBodyTest {

    private val client = ApiClient(
        "wl_test_abcdefghijklmnopqrstuvwxyz012345",
        "http://localhost:1"
    )
    private val identifiedClient = ApiClient(
        "wl_test_abcdefghijklmnopqrstuvwxyz012345",
        "http://localhost:1",
        PACKAGE_NAME
    )
    private val linkId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    @Test
    fun `referrer body includes required fingerprint_version`() {
        val body = client.buildAttributionBody(null, "1.1.0", null, linkId)
        assertEquals(linkId, body.getString("referrer"))
        assertEquals("basic", body.getString("fingerprint_version"))
        assertEquals("android", body.getString("platform"))
    }

    @Test
    fun `WL-S13 enriched body carries the language, the zone and the offset`() {
        val signals = DeviceSignals("en-US", -300, "America/Toronto")
        val body = client.buildAttributionBody(signals, "1.1.0", null, null)

        // The zone name is the finer key; the offset stays for servers that
        // have not shipped zone-name matching yet.
        assertEquals("enriched_tz", body.getString("fingerprint_version"))
        assertEquals("America/Toronto", body.getString("timezone"))
        assertEquals("en-US", body.getString("accept_language"))
        assertEquals(-300, body.getInt("timezone_offset"))
        assertEquals("android", body.getString("platform"))
        assertEquals("1.1.0", body.getString("sdk_version"))
    }

    @Test
    fun `falls back to the offset variant when no zone is available`() {
        val signals = DeviceSignals("en-US", -300, "")
        val body = client.buildAttributionBody(signals, "1.1.0", null, null)

        assertEquals("enriched", body.getString("fingerprint_version"))
        assertFalse(body.has("timezone"))
    }

    @Test
    fun `enriched body drops user_agent and screen dimensions`() {
        val signals = DeviceSignals("en-US", -300)
        val body = client.buildAttributionBody(signals, "1.1.0", null, null)

        assertFalse(body.has("user_agent"))
        assertFalse(body.has("screen_width"))
        assertFalse(body.has("screen_height"))
    }

    @Test
    fun `deviceId is included when present`() {
        val signals = DeviceSignals("en-US", -300)
        val body = client.buildAttributionBody(signals, "1.1.0", "device-123", null)
        assertTrue(body.has("device_id"))
        assertEquals("device-123", body.getString("device_id"))
    }

    @Test
    fun `is_reinstall rides both match paths`() {
        val signals = DeviceSignals("en-US", -300)
        val enriched = client.buildAttributionBody(signals, "1.1.0", null, null, true)
        val referrer = client.buildAttributionBody(null, "1.1.0", null, linkId, true)

        // Both paths write the flag; a deterministic reinstall must not lose its
        // tag any more than a probabilistic one.
        assertTrue(enriched.getBoolean("is_reinstall"))
        assertTrue(referrer.getBoolean("is_reinstall"))
    }

    @Test
    fun `is_reinstall is false for a first install`() {
        val signals = DeviceSignals("en-US", -300)
        val body = client.buildAttributionBody(signals, "1.1.0", null, null)

        // Sent explicitly rather than omitted. The server stores an absent field
        // as unknown, not false, so pre-1.1.0 SDKs are recorded honestly rather
        // than as first installs.
        assertTrue(body.has("is_reinstall"))
        assertFalse(body.getBoolean("is_reinstall"))
    }

    @Test
    fun `enriched body includes app_package_name`() {
        val signals = DeviceSignals("en-US", -300)
        val body = identifiedClient.buildAttributionBody(signals, "1.1.0", null, null)
        assertEquals(PACKAGE_NAME, body.getString("app_package_name"))
    }

    @Test
    fun `referrer body includes app_package_name`() {
        val body = identifiedClient.buildAttributionBody(null, "1.1.0", null, linkId)
        assertEquals(PACKAGE_NAME, body.getString("app_package_name"))
    }

    @Test
    fun `app_package_name is omitted when unknown`() {
        val signals = DeviceSignals("en-US", -300)
        val body = client.buildAttributionBody(signals, "1.1.0", null, null)
        assertFalse(body.has("app_package_name"))
    }

    @Test
    fun `app_package_name is omitted when empty`() {
        // The server rejects an empty app_package_name, which would 400 the
        // whole request and lose the match. An unusable package name degrades
        // to the org-wide lookup instead.
        val blankClient = ApiClient(
            "wl_test_abcdefghijklmnopqrstuvwxyz012345",
            "http://localhost:1",
            ""
        )
        val signals = DeviceSignals("en-US", -300)
        val body = blankClient.buildAttributionBody(signals, "1.1.0", null, null)
        assertFalse(body.has("app_package_name"))
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.myapp"
    }
}
