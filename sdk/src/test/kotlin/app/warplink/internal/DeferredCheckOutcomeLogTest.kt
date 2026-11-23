package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.DeferredCheckStatus
import app.warplink.idleMainLooperUntil
import app.warplink.internal.referral.ReferralFixtures
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WL-S75: the log says what the check did, not what its first request happened to carry. */
@RunWith(RobolectricTestRunner::class)
class DeferredCheckOutcomeLogTest {

    private lateinit var context: Context
    private lateinit var device: TrafficDevice

    @Before
    fun setUp() {
        ShadowLog.clear()
        context = ApplicationProvider.getApplicationContext()
        device = TrafficDevice(context).also { it.logger = Logger(true) }
    }

    @After
    fun tearDown() = device.close()

    private fun lines(prefix: String) =
        ShadowLog.getLogsForTag("WarpLink").map { it.msg }.filter { it.startsWith(prefix) }

    @Test
    fun `WL-S75 a retry time prints in whole seconds`() {
        val retryAtMs = 1_800_000_000_123L

        assertEquals(
            "waiting, retry at ${Instant.ofEpochSecond(1_800_000_000L)}",
            describeStatus(DeferredCheckStatus.Waiting(retryAtMs)),
        )
    }

    @Test
    fun `WL-S75 a token redeem that falls through to a request with signals says raw signals yes`() {
        val raw = "utm_source=warplink&utm_content=${ReferralFixtures.LINK_ID}&wl_rt=${ReferralFixtures.validToken()}"

        device.launch(device.answeringTwice(), referrer = raw)

        assertEquals(listOf("Deferred check request: attempt 1, raw signals yes"), lines("Deferred check request:"))
    }

    @Test
    fun `WL-S75 a check a reconfigure abandoned logs no outcome, and its replacement logs one`() {
        val release = CountDownLatch(1)
        val held = device.silent().also { it.serveOnce(release) }
        val storage = device.storage()
        val coordinator = DeferredCheckCoordinator()
        val firstDone = CountDownLatch(1)
        coordinator.check(device.inputs(storage, held)) { firstDone.countDown() }
        assertTrue(held.requestReceived.await(10, TimeUnit.SECONDS), "the check never reached the wire")
        coordinator.abandon()

        val secondDone = CountDownLatch(1)
        coordinator.check(device.inputs(storage, device.answering())) { secondDone.countDown() }
        idleMainLooperUntil(secondDone)
        release.countDown()
        idleMainLooperUntil(firstDone)

        assertEquals(listOf("Deferred check: noMatch"), lines("Deferred check:"))
    }
}
