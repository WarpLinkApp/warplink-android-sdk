package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import app.warplink.WarpLinkError
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WL-S75: what the debug log says about a deferred check, once per request and once per outcome. */
@RunWith(RobolectricTestRunner::class)
class DeferredCheckLogTest {

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
    fun `WL-S75 a request with raw signals logs its attempt number once`() {
        device.launch(device.answering())

        assertEquals(listOf("Deferred check request: attempt 1, raw signals yes"), lines("Deferred check request:"))
    }

    @Test
    fun `WL-S75 a check logs one request line however many times it retries inside the launch`() {
        device.launch(device.failing())

        assertEquals(
            listOf("Deferred check request: attempt 1, raw signals yes"),
            lines("Deferred check request:"),
            "the run sent three requests, all attempt 1",
        )
    }

    @Test
    fun `WL-S75 each failed request says what it ran into and whether the launch tries again`() {
        device.launch(device.failing())

        assertEquals(
            listOf(
                "Deferred check request got status 500 (try 1 of 3), trying again",
                "Deferred check request got status 500 (try 2 of 3), trying again",
                "Deferred check request got status 500 (try 3 of 3)",
            ),
            lines("Deferred check request got"),
        )
    }

    @Test
    fun `WL-S75 a request cut off by its time bound says it timed out and for how long`() {
        val timeout = WarpLinkError.NetworkError(SocketTimeoutException("attempt abandoned after 3000ms"))

        assertEquals(
            "Deferred check request timed out after 3 s (try 2 of 3), trying again",
            describeFailedTry(timeout, tryNumber = 2, willRetry = true, settings = RetrySettings.DEFAULT),
        )
        assertEquals(
            "Deferred check request timed out after 4 s (try 1 of 3), trying again",
            describeFailedTry(timeout, tryNumber = 1, willRetry = true, settings = RetrySettings.DEFAULT),
        )
    }

    @Test
    fun `WL-S75 a refused connection says the server could not be reached`() {
        val refused = WarpLinkError.NetworkError(ConnectException("refused"))

        assertEquals(
            "Deferred check request could not reach the server (try 3 of 3)",
            describeFailedTry(refused, tryNumber = 3, willRetry = false, settings = RetrySettings.DEFAULT),
        )
    }

    @Test
    fun `WL-S75 a request that carries only a referrer past the raw signal age says raw signals no`() {
        device.installedDaysAgo(3)

        device.launch(device.answering(), referrer = LINK_REFERRER)

        assertEquals(listOf("Deferred check request: attempt 1, raw signals no"), lines("Deferred check request:"))
    }

    @Test
    fun `WL-S75 a held back check sends no request line`() {
        device.installedDaysAgo(31)

        device.launch(device.silent())

        assertTrue(lines("Deferred check request:").isEmpty())
    }

    @Test
    fun `WL-S75 a matched check logs its outcome once`() {
        device.launch(device.answering(LINK_MATCH))

        assertEquals(listOf("Deferred check: matched"), lines("Deferred check:"))
    }

    @Test
    fun `WL-S75 a check with no match logs its outcome once`() {
        device.launch(device.answering(NO_MATCH))

        assertEquals(listOf("Deferred check: noMatch"), lines("Deferred check:"))
    }

    @Test
    fun `WL-S75 a completed check answering from the cache logs its outcome once`() {
        device.launch(device.answering(NO_MATCH))
        ShadowLog.clear()

        device.launch(device.silent())

        assertEquals(listOf("Deferred check: noMatch"), lines("Deferred check:"))
    }

    @Test
    fun `WL-S75 a failed check logs the time it will retry`() {
        device.launch(device.failing())

        val retryAt = Instant.ofEpochMilli(device.nowMs + 900_000)
        assertEquals(listOf("Deferred check: waiting, retry at $retryAt"), lines("Deferred check:"))
    }

    @Test
    fun `WL-S75 a launch inside the wait logs the decision once and nothing else`() {
        device.launch(device.failing())
        ShadowLog.clear()
        device.advanceMs(1_000)

        device.launch(device.silent())

        val retryAt = Instant.ofEpochMilli(device.nowMs - 1_000 + 900_000)
        assertEquals(listOf("Deferred check: waiting, retry at $retryAt"), lines("Deferred check"))
    }

    @Test
    fun `WL-S75 an install too old logs the decision once`() {
        device.installedDaysAgo(31)

        device.launch(device.silent())

        assertEquals(listOf("Deferred check: skippedOldInstall"), lines("Deferred check"))
    }

    @Test
    fun `WL-S75 a check that gave up logs the decision once`() {
        device.launch(device.failing())
        device.advanceMs(259_200_000)
        ShadowLog.clear()

        device.launch(device.silent())

        assertEquals(listOf("Deferred check: stopped"), lines("Deferred check"))
    }
}
