package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.internal.referral.ReferralFixtures
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** WL-S72, WL-S58 and WL-S73: what a match request carries about the install, its attempts and its Play referrer. */
@RunWith(RobolectricTestRunner::class)
class DeferredRequestFieldsTest {

    private lateinit var context: Context
    private lateinit var device: TrafficDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        device = TrafficDevice(context)
    }

    @After
    fun tearDown() = device.close()

    private fun body(server: LoopbackJsonServer, index: Int = 0) = JSONObject(server.requestBodies[index])

    @Test
    fun `WL-S72 the first launch sends attempt number 1 and an installation id`() {
        val server = device.answering()

        device.launch(server)

        val sent = body(server)
        assertEquals(1, sent.getInt("attempt_number"))
        assertEquals(sent.getString("installation_id"), UUID.fromString(sent.getString("installation_id")).toString())
    }

    @Test
    fun `WL-S58 WL-S72 the attempt number counts launches and the installation id stays`() {
        val first = device.failing()
        device.launch(first)
        device.advanceMs(900_000)
        val second = device.failing()
        device.launch(second)

        assertEquals(setOf(1), first.requestBodies.indices.map { body(first, it).getInt("attempt_number") }.toSet())
        assertEquals(setOf(2), second.requestBodies.indices.map { body(second, it).getInt("attempt_number") }.toSet())
        assertEquals(body(first).getString("installation_id"), body(second).getString("installation_id"))
    }

    @Test
    fun `WL-S72 a launch held back by a rule does not use up an attempt number`() {
        device.launch(device.failing())
        device.advanceMs(1_000)
        device.launch(device.silent())
        device.advanceMs(900_000)
        val next = device.answering()

        device.launch(next)

        assertEquals(2, body(next).getInt("attempt_number"))
    }

    @Test
    fun `WL-S58 WL-S72 a reinstall sends a new installation id`() {
        val first = device.answering()
        device.launch(first)
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
        val second = device.answering()

        device.launch(second)

        assertNotEquals(body(first).getString("installation_id"), body(second).getString("installation_id"))
        assertEquals(1, body(second).getInt("attempt_number"))
    }

    @Test
    fun `WL-S58 a match request from a redeem carries the installation id but no attempt number`() {
        val body = ApiClient(LIVE_KEY, "http://127.0.0.1:1").buildAttributionBody(
            signals = null, sdkVersion = "1.4.0", deviceId = null, referrer = null,
            installationId = UUID.randomUUID().toString(),
        )

        assertTrue(body.has("installation_id"))
        assertFalse(body.has("attempt_number"))
    }

    @Test
    fun `WL-S73 an organic Play referrer is sent whole and no link id is invented`() {
        val server = device.answering()

        device.launch(server, referrer = ORGANIC)

        val sent = body(server)
        assertEquals(ORGANIC, sent.getString("install_referrer"))
        assertFalse(sent.has("referrer"), "this referrer names no WarpLink link")
        assertTrue(sent.has("accept_language"), "the raw signals still ride along")
    }

    @Test
    fun `WL-S73 a referrer with a link id sends the parsed link id and not the raw string`() {
        val server = device.answering()
        val raw = "utm_source=warplink&utm_content=$LINK_ID&utm_medium=organic"

        device.launch(server, referrer = raw)

        val sent = body(server)
        assertEquals(LINK_ID, sent.getString("referrer"))
        assertFalse(sent.has("install_referrer"), "the server takes the raw string only when no link id was read")
    }

    @Test
    fun `WL-S73 a recovery token never goes out inside the raw referrer`() {
        val token = ReferralFixtures.validToken()
        val raw = "utm_source=warplink&utm_content=$LINK_ID&wl_rt=$token"
        val server = device.answeringTwice()

        device.launch(server, referrer = raw)

        assertEquals(token, body(server, 0).getString("recovery_token"))
        assertFalse(body(server, 0).has("install_referrer"), "the token request carries nothing else")
        assertEquals(LINK_ID, body(server, 1).getString("referrer"))
        assertFalse(body(server, 1).has("install_referrer"), "the token is not sent again in the raw string")
        assertFalse(server.requestBodies[1].contains(token))
    }

    @Test
    fun `WL-S73 no Play referrer sends no install referrer field`() {
        val server = device.answering()

        device.launch(server, referrer = "")

        assertFalse(body(server).has("install_referrer"))
    }

    @Test
    fun `WL-S73 the body trims the referrer, cuts it to 512 characters and omits an empty one`() {
        val client = ApiClient(LIVE_KEY, "http://127.0.0.1:1")
        fun sent(value: String?) = client.buildAttributionBody(
            signals = null, sdkVersion = "1.4.0", deviceId = null, referrer = null, installReferrer = value,
        )

        assertEquals("utm_medium=organic", sent("  utm_medium=organic\n").getString("install_referrer"))
        assertEquals(512, sent("a".repeat(600)).getString("install_referrer").length)
        assertFalse(sent("   ").has("install_referrer"))
        assertFalse(sent(null).has("install_referrer"))
    }

    private companion object {
        const val ORGANIC = "utm_source=google-play&utm_medium=organic"
        const val LINK_ID = ReferralFixtures.LINK_ID
    }
}
