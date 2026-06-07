package app.warplink.internal

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The server's attributionMatchSchema requires fingerprint_version on every
 * POST /attribution/match request, including the deterministic Play Install
 * Referrer path (it has no enriched signals but must still send the field, or
 * the API rejects the request with a 400 validation error).
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientAttributionBodyTest {

    private val client = ApiClient(
        "wl_test_abcdefghijklmnopqrstuvwxyz012345",
        "http://localhost:1"
    )
    private val linkId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    @Test
    fun `referrer body includes required fingerprint_version`() {
        val body = client.buildAttributionBody(null, "0.1.0", null, linkId)
        assertEquals(linkId, body.getString("referrer"))
        assertEquals("basic", body.getString("fingerprint_version"))
    }

    @Test
    fun `enriched signals body includes fingerprint_version`() {
        val signals = DeviceSignals("en-US", 1080, 1920, -300, "ua")
        val body = client.buildAttributionBody(signals, "0.1.0", null, null)
        assertEquals("enriched", body.getString("fingerprint_version"))
    }
}
