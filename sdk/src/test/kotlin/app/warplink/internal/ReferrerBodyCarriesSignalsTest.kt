package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.idleMainLooperUntil
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Play Install Referrer names exactly one link id. That link can be deleted,
 * expired, or owned by another org or another app, in which case the server's
 * referrer branch produces no response and the cascade falls through to
 * device_id > fingerprint > raw signals (see runMatchCascade). The fall-through
 * can only use what is in the same request body: there is no second request,
 * because the deferred check fires once per install and `deferredCheckCompleted`
 * outlives every relaunch.
 *
 * A referrer request carrying nothing but the referrer therefore loses the
 * install permanently, and labelling it `fingerprint_version: "basic"` also
 * mislabels the attempt row that is meant to explain the loss.
 *
 * Two halves have to hold for this to work, so both are pinned here: ApiClient
 * must stop returning early on the referrer branch, and DeferredDeepLink's
 * matchWithReferrer must stop passing `signals = null`. Fixing either alone is
 * a no-op end to end.
 */
@RunWith(RobolectricTestRunner::class)
class ReferrerBodyCarriesSignalsTest {

    private lateinit var context: Context
    private val originalTz = TimeZone.getDefault()
    private val originalLocale = Locale.getDefault()
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
        // Pinned so the expected signal values are exact rather than
        // whatever the host JVM happens to default to.
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.CANADA)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
        Locale.setDefault(originalLocale)
        stubs.forEach { it.close() }
    }

    @Test
    fun `a referrer request carries the raw signals as well`() {
        val body = runReferrerCheck()

        // Confirms the request really took the referrer branch, so the
        // assertions below are about that branch and not the fingerprint one.
        assertEquals(LINK_ID, body.optString("referrer"), "not the referrer path: $body")
        // These are the only things the server has left once the referrer's link
        // turns out to be deleted or foreign. Absent, the cascade reaches the
        // raw-signals tier with nothing to hash and the install is lost for good.
        assertEquals(
            "en-CA", body.optString("accept_language"),
            "referrer request sent no language, so a fallback match is impossible: $body"
        )
        assertEquals(
            ZONE, body.optString("timezone"),
            "referrer request sent no zone name, the highest-entropy signal: $body"
        )
        assertTrue(
            body.has("timezone_offset"),
            "referrer request sent no offset, the fallback for zone-less servers: $body"
        )
    }

    @Test
    fun `a referrer request describes what it actually sent`() {
        val body = runReferrerCheck()

        // fingerprint_version picks the server's hashing recipe and is stored on
        // the attempt row. Saying "basic" while sending a zone name both blocks
        // the enriched recipe and makes the telemetry read as "device had no
        // signals", which points anyone debugging a lost install the wrong way.
        assertEquals(
            "enriched_tz", body.optString("fingerprint_version"),
            "referrer request mislabels its own contents: $body"
        )
    }

    @Test
    fun `the body builder keeps the signals when a referrer is present`() {
        val client = ApiClient(API_KEY, "http://localhost:1")

        val body = client.buildAttributionBody(
            DeviceSignals("en-US", -300, "America/Toronto"),
            "1.1.0",
            null,
            LINK_ID
        )

        // The ApiClient half on its own: the referrer early-return drops every
        // signal handed to it. This fails independently of the wire tests above
        // so a partial fix cannot look green.
        assertEquals(LINK_ID, body.optString("referrer"))
        assertEquals(
            "en-US", body.optString("accept_language"),
            "the referrer branch discarded the signals it was given: $body"
        )
        assertEquals("America/Toronto", body.optString("timezone"), "$body")
        assertTrue(body.has("timezone_offset"), "$body")
        assertEquals("enriched_tz", body.optString("fingerprint_version"), "$body")
    }

    @Test
    fun `a referrer request with no signals still declares basic`() {
        val client = ApiClient(API_KEY, "http://localhost:1")

        val body = client.buildAttributionBody(null, "1.1.0", null, LINK_ID)

        // The honest case, and the reason the label is not simply hardcoded to
        // enriched: a caller with genuinely nothing to send must still satisfy
        // the schema's required fingerprint_version.
        assertEquals("basic", body.optString("fingerprint_version"), "$body")
    }

    /**
     * Drives one full deferred check down the referrer branch against a stub
     * that answers "no match", and returns the body as it left the device.
     */
    private fun runReferrerCheck(): JSONObject {
        val stub = LoopbackJsonServer(NO_MATCH_JSON).also { stubs.add(it) }
        stub.serveOnce()
        val done = CountDownLatch(1)

        performDeferredCheck(
            Storage(context),
            FingerprintCollector(),
            ApiClient(API_KEY, stub.baseUrl),
            ReferrerSource { it(Result.success(ReferrerRead(LINK_ID, 0L))) },
            null
        ) { done.countDown() }

        idleMainLooperUntil(done)

        assertTrue(
            stub.requestReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS),
            "the deferred check sent no attribution request at all"
        )
        return JSONObject(stub.requestBody)
    }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val NO_MATCH_JSON = """{"matched":false}"""
        const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        const val ZONE = "America/Toronto"
        const val AWAIT_SECONDS = 5L
    }
}
