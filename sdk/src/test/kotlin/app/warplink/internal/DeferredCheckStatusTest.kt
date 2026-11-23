package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.DeferredCheckStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * WL-S74: the status a developer reads is derived from what the install stored,
 * so it survives a relaunch, and a `wl_test_` key never reports a hold-back.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredCheckStatusTest {

    private lateinit var context: Context
    private lateinit var device: TrafficDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        device = TrafficDevice(context)
    }

    @After
    fun tearDown() = device.close()

    /** What a fresh process reads: a new [Storage] over the files the earlier launches left. */
    private fun status(running: Boolean = false, on: TrafficDevice = device) =
        deferredCheckStatusOf(on.storage(), running, on.nowMs)

    @Test
    fun `WL-S74 an install that has not checked yet is NotChecked`() {
        assertEquals(DeferredCheckStatus.NotChecked, status())
    }

    @Test
    fun `WL-S74 a check that is running or waiting for the app to come up is Checking`() {
        assertEquals(DeferredCheckStatus.Checking, status(running = true))
    }

    @Test
    fun `WL-S74 a check running after an earlier failure is Checking, not Waiting`() {
        device.launch(device.failing())
        device.advanceMs(900_000)

        assertEquals(DeferredCheckStatus.Checking, status(running = true))
    }

    @Test
    fun `WL-S74 a check on the wire is Checking even when the install is old or the check has given up`() {
        device.installedDaysAgo(31)
        assertEquals(DeferredCheckStatus.Checking, status(running = true))

        device.installedDaysAgo(0)
        device.launch(device.failing())
        device.advanceMs(259_200_000)
        assertEquals(DeferredCheckStatus.Checking, status(running = true))
    }

    @Test
    fun `WL-S74 a check that matched a link is Matched, and stays Matched after a relaunch`() {
        device.launch(device.answering(LINK_MATCH))

        assertEquals(DeferredCheckStatus.Matched, status())
        device.advanceMs(40L * 86_400_000L)
        assertEquals(DeferredCheckStatus.Matched, status(), "a completed check outlives the install age rule")
    }

    @Test
    fun `WL-S74 a check that found nothing is NoMatch`() {
        device.launch(device.answering(NO_MATCH))

        assertEquals(DeferredCheckStatus.NoMatch, status())
    }

    @Test
    fun `WL-S74 a failed check is Waiting until the first delay passes, and a relaunch agrees`() {
        device.launch(device.failing())
        val failedAt = device.nowMs

        assertEquals(DeferredCheckStatus.Waiting(failedAt + 900_000), status())
        device.advanceMs(899_999)
        assertEquals(DeferredCheckStatus.Waiting(failedAt + 900_000), status())
    }

    @Test
    fun `WL-S74 the retry time follows the delay of the failure count`() {
        device.launch(device.failing())
        device.advanceMs(900_000)
        device.launch(device.failing())
        val secondFailureAt = device.nowMs

        assertEquals(DeferredCheckStatus.Waiting(secondFailureAt + 3_600_000), status())
    }

    @Test
    fun `WL-S74 once the delay has passed no check runs and none waits, so it reads NotChecked`() {
        device.launch(device.failing())
        device.advanceMs(900_000)

        assertEquals(DeferredCheckStatus.NotChecked, status())
    }

    @Test
    fun `WL-S74 an install older than 30 days that never checked is SkippedOldInstall`() {
        device.installedDaysAgo(31)

        assertEquals(DeferredCheckStatus.SkippedOldInstall, status())
    }

    @Test
    fun `WL-S74 three days after the first failure the check has Stopped`() {
        device.launch(device.failing())
        device.advanceMs(259_200_000)

        assertEquals(DeferredCheckStatus.Stopped, status())
    }

    @Test
    fun `WL-S74 a 200 answer after a failure ends Waiting`() {
        device.launch(device.failing())
        device.advanceMs(900_000)
        device.launch(device.answering(NO_MATCH))

        assertEquals(DeferredCheckStatus.NoMatch, status())
    }

    @Test
    fun `WL-S74 a test key never reports Waiting after a failure`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.launch(testDevice.failing())

        assertEquals(DeferredCheckStatus.NotChecked, status(on = testDevice))
        testDevice.close()
    }

    @Test
    fun `WL-S74 a test key never reports SkippedOldInstall or Stopped`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.launch(testDevice.failing())
        testDevice.installedDaysAgo(60)
        testDevice.advanceMs(259_200_000)

        assertEquals(DeferredCheckStatus.NotChecked, status(on = testDevice))
        testDevice.close()
    }

    @Test
    fun `WL-S74 a test key reports Matched after a match`() {
        val testDevice = TrafficDevice(context, TEST_KEY)
        testDevice.launch(testDevice.answering(LINK_MATCH))

        assertEquals(DeferredCheckStatus.Matched, status(on = testDevice))
        testDevice.close()
    }
}
