package app.warplink.internal

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** WL-S69: the SDK keeps built-in defaults and clamps whatever `sdk_policy` the server sends. */
@RunWith(RobolectricTestRunner::class)
class SdkPolicyTest {

    @Test
    fun `WL-S69 the built-in defaults are the contract values`() {
        val defaults = SdkPolicy.DEFAULT

        assertEquals(listOf(900L, 3_600L, 21_600L, 86_400L), defaults.retryDelaysSeconds)
        assertEquals(259_200L, defaults.giveUpAfterSeconds)
        assertEquals(2_592_000L, defaults.maxInstallAgeSeconds)
        assertEquals(172_800L, defaults.rawSignalMaxInstallAgeSeconds)
    }

    @Test
    fun `WL-S69 a well formed block replaces the defaults`() {
        val parsed = SdkPolicy.fromJson(
            JSONObject(
                """{"retry_delays_seconds":[120,240],"give_up_after_seconds":7200,
                   "max_install_age_seconds":864000,"raw_signal_max_install_age_seconds":43200}"""
            )
        )

        assertEquals(
            SdkPolicy(listOf(120L, 240L), 7_200L, 864_000L, 43_200L),
            parsed
        )
    }

    @Test
    fun `WL-S69 each value is clamped to its range`() {
        val low = SdkPolicy.fromJson(
            JSONObject(
                """{"retry_delays_seconds":[1],"give_up_after_seconds":1,
                   "max_install_age_seconds":1,"raw_signal_max_install_age_seconds":1}"""
            )
        )
        val high = SdkPolicy.fromJson(
            JSONObject(
                """{"retry_delays_seconds":[99999999],"give_up_after_seconds":99999999999,
                   "max_install_age_seconds":99999999999,"raw_signal_max_install_age_seconds":99999999999}"""
            )
        )

        assertEquals(listOf(60L), low?.retryDelaysSeconds)
        assertEquals(3_600L, low?.giveUpAfterSeconds)
        assertEquals(86_400L, low?.maxInstallAgeSeconds)
        assertEquals(3_600L, low?.rawSignalMaxInstallAgeSeconds)
        assertEquals(listOf(604_800L), high?.retryDelaysSeconds)
        assertEquals(2_592_000L, high?.giveUpAfterSeconds)
        assertEquals(31_536_000L, high?.maxInstallAgeSeconds)
        assertEquals(31_536_000L, high?.rawSignalMaxInstallAgeSeconds)
    }

    @Test
    fun `WL-S69 the raw signal age never exceeds the clamped max install age`() {
        val parsed = SdkPolicy.fromJson(
            JSONObject(
                """{"retry_delays_seconds":[900],"give_up_after_seconds":7200,
                   "max_install_age_seconds":172800,"raw_signal_max_install_age_seconds":999999}"""
            )
        )

        assertEquals(172_800L, parsed?.rawSignalMaxInstallAgeSeconds)
    }

    @Test
    fun `WL-S69 a missing or bad block is no policy at all`() {
        assertNull(SdkPolicy.fromJson(null))
        assertNull(SdkPolicy.fromJson(JSONObject("{}")))
        assertNull(SdkPolicy.fromJson(JSONObject("""{"retry_delays_seconds":"soon"}""")))
        assertNull(SdkPolicy.fromJson(JSONObject("""{"retry_delays_seconds":[]}""")))
    }

    @Test
    fun `WL-S69 a bad field keeps its default while the good fields apply`() {
        val parsed = SdkPolicy.fromJson(
            JSONObject("""{"retry_delays_seconds":[300],"give_up_after_seconds":"never"}""")
        )

        assertEquals(listOf(300L), parsed?.retryDelaysSeconds)
        assertEquals(SdkPolicy.DEFAULT.giveUpAfterSeconds, parsed?.giveUpAfterSeconds)
    }

    @Test
    fun `the delay after a failure follows the list and then repeats its last value`() {
        val policy = SdkPolicy.DEFAULT

        assertEquals(900L, policy.retryDelayAfterFailure(1))
        assertEquals(3_600L, policy.retryDelayAfterFailure(2))
        assertEquals(21_600L, policy.retryDelayAfterFailure(3))
        assertEquals(86_400L, policy.retryDelayAfterFailure(4))
        assertEquals(86_400L, policy.retryDelayAfterFailure(9))
    }

    @Test
    fun `a stored policy reads back as the one that was saved`() {
        val policy = SdkPolicy(listOf(120L), 7_200L, 864_000L, 43_200L)

        assertEquals(policy, SdkPolicy.fromJson(policy.toJson()))
    }
}
