package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.referral.ReferralFixtures
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WL-S63 and WL-S64: an install past the policy age sends nothing, and past the
 * raw signal age sends no raw device signals. Through the shared check path.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredAgeRulesTest {

    private lateinit var context: Context
    private lateinit var device: TrafficDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        device = TrafficDevice(context)
    }

    @After
    fun tearDown() = device.close()

    @Test
    fun `WL-S63 an install older than 30 days sends nothing and answers no match`() {
        device.installedDaysAgo(31)
        val server = device.silent()

        val result = device.launch(server)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertTrue(server.requestLines.isEmpty(), "an install this old must not reach the wire")
    }

    @Test
    fun `WL-S63 the age skip is a separate note and never the checked or device seen flag`() {
        device.installedDaysAgo(31)

        device.launch(device.silent())

        val storage = device.storage()
        assertTrue(storage.traffic.skippedForAge)
        assertFalse(storage.deferredCheckCompleted)
        assertFalse(storage.deviceHasCompletedAttribution)
        assertFalse(storage.isReinstall, "a skipped check must not make the next install look like a reinstall")
    }

    @Test
    fun `WL-S63 an install within 30 days is checked`() {
        device.installedDaysAgo(29)
        val server = device.answering()

        device.launch(server, referrer = LINK_REFERRER)

        assertEquals(1, server.requestLines.size)
    }

    @Test
    fun `WL-S63 a test key is checked whatever the install age`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.installedDaysAgo(400)
        val server = testDevice.answering()

        testDevice.launch(server)

        assertEquals(1, server.requestLines.size)
        testDevice.close()
    }

    @Test
    fun `WL-S64 a fresh install sends raw device signals`() {
        val server = device.answering()

        device.launch(server)

        assertTrue(server.requestBody.contains("\"accept_language\""))
    }

    @Test
    fun `WL-S64 an install older than 48 hours with no WarpLink referrer sends nothing and uses no attempt`() {
        device.installedDaysAgo(days = 2, hours = 1)
        val server = device.silent()

        val result = device.launch(server, referrer = ORGANIC)

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertTrue(server.requestLines.isEmpty(), "the server rejects a request with nothing to match on")
        assertEquals(0, device.storage().traffic.failureCount)
        assertEquals(1, device.storage().traffic.peekNextAttemptNumber(), "no attempt was used")
        assertFalse(device.storage().deferredCheckCompleted, "the check stays open, the age rules decide later")
    }

    @Test
    fun `WL-S64 an install older than 48 hours with only an expired recovery token sends nothing and uses no attempt`() {
        device.installedDaysAgo(days = 2, hours = 1)
        val server = device.silent()

        val result = device.launch(server, referrer = "utm_source=warplink&wl_rt=${ReferralFixtures.expiredToken()}")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertTrue(server.requestLines.isEmpty(), "a request with no referrer, no token and no signals is rejected")
        assertEquals(0, device.storage().traffic.failureCount)
        assertEquals(1, device.storage().traffic.peekNextAttemptNumber(), "no attempt was used")
    }

    @Test
    fun `WL-S64 an install older than 48 hours with no Play Store sends nothing`() {
        device.installedDaysAgo(days = 2, hours = 1)
        val server = device.silent()

        device.launch(server)

        assertTrue(server.requestLines.isEmpty())
    }

    @Test
    fun `WL-S64 an install older than 48 hours with a WarpLink referrer sends the link id and no raw signals`() {
        device.installedDaysAgo(days = 2, hours = 1)
        val server = device.answering()

        device.launch(server, referrer = LINK_REFERRER)

        assertEquals(1, server.requestLines.size)
        assertTrue(server.requestBody.contains(ReferralFixtures.LINK_ID))
        assertFalse(server.requestBody.contains("accept_language"))
        assertFalse(server.requestBody.contains("timezone"))
        assertTrue(server.requestBody.contains("\"fingerprint_version\":\"basic\""))
    }

    @Test
    fun `WL-S64 a test key sends raw device signals on an old install`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.installedDaysAgo(10)
        val server = testDevice.answering()

        testDevice.launch(server)

        assertTrue(server.requestBody.contains("\"accept_language\""))
        testDevice.close()
    }

    private companion object {
        const val ORGANIC = "utm_source=google-play&utm_medium=organic"
    }
}
