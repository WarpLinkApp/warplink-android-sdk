package app.warplink.internal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The install-age and backoff rules as pure decisions. WL-S63 age skip, WL-S64
 * raw signal age, WL-S65 backoff, WL-S66 give up.
 */
class TrafficDecisionTest {

    private val policy = SdkPolicy.DEFAULT
    private val now = 10_000_000_000L
    private val noFailures = FailureRecord()

    private fun decide(
        ageSeconds: Long = 0,
        failures: FailureRecord = noFailures,
        at: Long = now,
    ) = decideTraffic(policy, ageSeconds, failures, at)

    private fun failedAt(msAgo: Long, count: Int = 1, firstMsAgo: Long = msAgo) =
        FailureRecord(count, now - firstMsAgo, now - msAgo)

    @Test
    fun `WL-S63 a fresh install proceeds with raw signals`() {
        assertEquals(TrafficDecision.Proceed(sendRawSignals = true), decide(ageSeconds = 3_600))
    }

    @Test
    fun `WL-S63 an install older than 30 days sends nothing`() {
        assertEquals(TrafficDecision.SkipInstallAge, decide(ageSeconds = 2_592_001))
    }

    @Test
    fun `WL-S63 an install exactly 30 days old still proceeds`() {
        assertIs<TrafficDecision.Proceed>(decide(ageSeconds = 2_592_000))
    }

    @Test
    fun `WL-S64 an install older than 48 hours proceeds without raw signals`() {
        assertEquals(TrafficDecision.Proceed(sendRawSignals = false), decide(ageSeconds = 172_801))
        assertEquals(TrafficDecision.Proceed(sendRawSignals = true), decide(ageSeconds = 172_800))
    }

    @Test
    fun `WL-S65 a check inside the wait after a failure is held`() {
        val decision = decide(failures = failedAt(msAgo = 60_000))

        assertEquals(TrafficDecision.WaitForRetry(secondsLeft = 840), decision)
    }

    @Test
    fun `WL-S65 a check after the wait proceeds`() {
        assertIs<TrafficDecision.Proceed>(decide(failures = failedAt(msAgo = 900_000)))
    }

    @Test
    fun `WL-S65 the wait grows with the failure count and then repeats the last delay`() {
        assertIs<TrafficDecision.WaitForRetry>(decide(failures = failedAt(msAgo = 3_000_000, count = 2)))
        assertIs<TrafficDecision.Proceed>(decide(failures = failedAt(msAgo = 3_600_000, count = 2)))
        assertIs<TrafficDecision.WaitForRetry>(decide(failures = failedAt(msAgo = 80_000_000, count = 7, firstMsAgo = 200_000_000)))
        assertIs<TrafficDecision.Proceed>(decide(failures = failedAt(msAgo = 86_400_000, count = 7, firstMsAgo = 200_000_000)))
    }

    @Test
    fun `WL-S66 the check stops for good three days after the first failure`() {
        val firstMsAgo = 259_200_000L

        assertEquals(
            TrafficDecision.GaveUp,
            decide(failures = failedAt(msAgo = 90_000_000, count = 4, firstMsAgo = firstMsAgo))
        )
        assertIs<TrafficDecision.Proceed>(
            decide(failures = failedAt(msAgo = 90_000_000, count = 4, firstMsAgo = firstMsAgo - 1))
        )
    }

    @Test
    fun `WL-S65 a clock that moved backwards never holds the check`() {
        assertIs<TrafficDecision.Proceed>(decide(failures = FailureRecord(1, now + 1_000, now + 1_000)))
    }

    @Test
    fun `WL-S63 the age skip comes before the wait`() {
        assertEquals(
            TrafficDecision.SkipInstallAge,
            decide(ageSeconds = 3_000_000, failures = failedAt(msAgo = 1_000))
        )
    }
}
