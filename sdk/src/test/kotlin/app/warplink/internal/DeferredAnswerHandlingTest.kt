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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WL-S68 and WL-S69: what a check saves from an answer, and when. */
@RunWith(RobolectricTestRunner::class)
class DeferredAnswerHandlingTest {

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
    fun `WL-S68 the gate is saved before the answer reaches the main thread`() {
        val server = device.answering(MATCH)
        val storage = device.storage()
        var answered = false
        DeferredCheckCoordinator().check(device.inputs(storage, server)) { answered = true }

        // The main looper is never idled here, so the hand-off to the host cannot
        // have run. This is a process killed between the answer and the callback.
        pollUntil("the gate was never saved") { synchronized(storage.stateLock) { storage.deferredCheckCompleted } }

        assertFalse(answered, "the host callback must still be waiting for the main thread")
        assertEquals("lnk_early", storage.cachedAttribution?.linkId)
        assertEquals(0, storage.traffic.failureCount)
        idleMainLooperUntil(message = "the callback never arrived") { answered }
    }

    @Test
    fun `WL-S68 a no match answer saves the gate before the main thread too`() {
        val server = device.answering(NO_MATCH)
        val storage = device.storage()
        var answered = false
        DeferredCheckCoordinator().check(device.inputs(storage, server)) { answered = true }

        pollUntil("the gate was never saved") { synchronized(storage.stateLock) { storage.deferredCheckCompleted } }

        assertFalse(answered)
        assertNull(storage.cachedAttribution)
        idleMainLooperUntil(message = "the callback never arrived") { answered }
    }

    @Test
    fun `an abandoned check gives its attempt back so the replacement is not held`() {
        val hold = CountDownLatch(1)
        val server = LoopbackJsonServer(NO_MATCH).also { it.serveOnce(hold) }
        val coordinator = DeferredCheckCoordinator()
        val storage = device.storage()
        coordinator.check(device.inputs(storage, server)) { }
        assertTrue(server.requestReceived.await(10, TimeUnit.SECONDS))
        assertEquals(1, storage.traffic.failureCount)

        coordinator.abandon()
        hold.countDown()
        server.close()

        assertEquals(0, storage.traffic.failureCount)
        val replacement = device.answering()
        device.launch(replacement)
        assertEquals(1, body(replacement).getInt("attempt_number"))
    }

    @Test
    fun `WL-S69 a policy in the answer is stored and used from then on`() {
        val server = device.answering(
            """{"matched":false,"sdk_policy":{"retry_delays_seconds":[120],"give_up_after_seconds":7200,
               "max_install_age_seconds":864000,"raw_signal_max_install_age_seconds":43200}}"""
        )

        device.launch(server)

        assertEquals(SdkPolicy(listOf(120L), 7_200L, 864_000L, 43_200L), device.storage().traffic.policy)
    }

    @Test
    fun `WL-S69 an answer without a policy keeps the defaults`() {
        device.launch(device.answering(NO_MATCH))

        assertEquals(SdkPolicy.DEFAULT, device.storage().traffic.policy)
    }

    @Test
    fun `WL-S69 an answer without a policy keeps the policy already stored`() {
        val stored = SdkPolicy(listOf(120L), 7_200L, 864_000L, 43_200L)
        device.storage().traffic.policy = stored

        device.launch(device.answering(NO_MATCH))

        assertEquals(stored, device.storage().traffic.policy)
    }

    @Test
    fun `WL-S69 the stored policy sets the wait after a failure`() {
        device.storage().traffic.policy = SdkPolicy(listOf(120L), 7_200L, 864_000L, 43_200L)
        device.launch(device.failing())

        device.advanceMs(119_000)
        val held = device.silent()
        device.launch(held)
        device.advanceMs(1_000)
        val retry = device.answering()
        device.launch(retry)

        assertTrue(held.requestLines.isEmpty())
        assertEquals(1, retry.requestLines.size)
    }

    @Test
    fun `WL-S69 a validate answer carries its policy`() {
        val parsed = parseValidate(
            """{"valid":true,"domains":[],"sdk_policy":{"retry_delays_seconds":[300],"give_up_after_seconds":7200,
               "max_install_age_seconds":864000,"raw_signal_max_install_age_seconds":43200}}"""
        )

        assertEquals(listOf(300L), parsed.policy?.retryDelaysSeconds)
        assertNull(parseValidate("""{"valid":true}""").policy)
    }

    @Test
    fun `clearing a test attribution resets attempts but keeps the installation id`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        val storage = testDevice.storage()
        val id = storage.traffic.installationId
        storage.traffic.beginAttempt(testDevice.nowMs)
        storage.traffic.noteSkippedForAge()

        assertTrue(storage.clearAttribution())

        assertEquals(0, storage.traffic.failureCount)
        assertFalse(storage.traffic.skippedForAge)
        assertEquals(id, storage.traffic.installationId)
        assertEquals(1, storage.traffic.peekNextAttemptNumber())
        testDevice.close()
    }

    private companion object {
        const val MATCH =
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"lnk_early","destination_url":"https://example.com/promo"}"""
    }
}
