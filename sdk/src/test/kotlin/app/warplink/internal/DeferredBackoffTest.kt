package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.WarpLinkError
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WL-S65, WL-S66 and WL-S67: a failed check backs off across launches, gives up
 * for good, and a 200 answer clears the record.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredBackoffTest {

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
    fun `WL-S65 a failed check holds the next launch back for the first delay`() {
        val failed = device.failing()
        assertIs<WarpLinkError.ServerError>(device.launch(failed).exceptionOrNull())

        device.advanceMs(899_000)
        val held = device.silent()
        val result = device.launch(held)

        assertTrue(result.isSuccess, "a held check is no match yet, not an error")
        assertNull(result.getOrNull())
        assertTrue(held.requestLines.isEmpty())
        assertFalse(device.storage().deferredCheckCompleted)
    }

    @Test
    fun `WL-S65 the next launch after the first delay sends again`() {
        device.launch(device.failing())

        device.advanceMs(900_000)
        val retry = device.answering()
        device.launch(retry)

        assertEquals(1, retry.requestLines.size)
    }

    @Test
    fun `WL-S65 each further failure waits longer, up to the last delay`() {
        val delays = listOf(900_000L, 3_600_000L, 21_600_000L, 86_400_000L)
        device.launch(device.failing())
        for (delay in delays) {
            device.advanceMs(delay - 1)
            val held = device.silent()
            device.launch(held)
            assertTrue(held.requestLines.isEmpty(), "still inside the ${delay}ms wait")
            device.advanceMs(1)
            device.launch(device.failing())
        }

        assertEquals(5, device.storage().traffic.failureCount)
    }

    @Test
    fun `WL-S65 a test key never waits`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.launch(testDevice.failing())
        val again = testDevice.answering()

        testDevice.launch(again)

        assertEquals(1, again.requestLines.size)
        testDevice.close()
    }

    @Test
    fun `WL-S66 the check stops for good three days after the first failure`() {
        device.launch(device.failing())
        device.advanceMs(259_200_000)
        val server = device.silent()

        val result = device.launch(server)

        assertNull(result.getOrNull())
        assertTrue(server.requestLines.isEmpty())
    }

    @Test
    fun `WL-S66 the check still runs just before the give up time`() {
        device.launch(device.failing())
        device.advanceMs(259_199_000)
        val server = device.answering()

        device.launch(server, referrer = LINK_REFERRER)

        assertEquals(1, server.requestLines.size)
    }

    @Test
    fun `WL-S67 a 200 answer clears the failures and closes the gate`() {
        device.launch(device.failing())
        device.advanceMs(900_000)

        device.launch(device.answering())

        val storage = device.storage()
        assertTrue(storage.deferredCheckCompleted)
        assertEquals(0, storage.traffic.failureCount)
    }

    @Test
    fun `a 200 answer with nothing to route counts as a failure`() {
        val unroutable = device.answering("""{"matched":true,"match_type":"probabilistic"}""")

        device.launch(unroutable)

        val storage = device.storage()
        assertFalse(storage.deferredCheckCompleted)
        assertEquals(1, storage.traffic.failureCount)
    }

    @Test
    fun `WL-S65 a launch whose process died mid request still counts as a failure`() {
        // The request is recorded when it is sent, so no completion has to run.
        device.storage().traffic.beginAttempt(device.nowMs)
        device.advanceMs(60_000)
        val held = device.silent()

        device.launch(held)

        assertTrue(held.requestLines.isEmpty())
    }

    @Test
    fun `a reinstall starts with no failures and a new installation id`() {
        device.launch(device.failing())
        val before = device.storage().traffic.installationId
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }

        val reinstalled = device.storage()

        assertEquals(0, reinstalled.traffic.failureCount)
        assertTrue(reinstalled.traffic.installationId != before)
    }
}
