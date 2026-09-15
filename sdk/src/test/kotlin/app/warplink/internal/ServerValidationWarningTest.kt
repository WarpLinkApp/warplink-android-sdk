package app.warplink.internal

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A key the server REJECTS is a different failure from a key that is malformed:
 * the format check passes, so nothing local complains, and the SDK then goes
 * quietly dead. Deep links stop resolving and no attribution is recorded, with
 * no signal a developer would notice.
 *
 * The warning therefore has to reach Logcat without debugLogging turned on, the
 * same as the malformed-key case ([app.warplink.WarpLinkTest] WL-S11).
 */
@RunWith(RobolectricTestRunner::class)
class ServerValidationWarningTest {

    private lateinit var storage: Storage

    @Before
    fun setUp() {
        ShadowLog.clear()
        storage = Storage(ApplicationProvider.getApplicationContext())
        storage.clearAll()
    }

    /** An ApiClient stand-in that answers validate with a fixed verdict. */
    private fun validating(response: Result<ValidateResponse>): ApiKeyValidating =
        ApiKeyValidating { callback -> callback(response) }

    private fun warnings(): List<String> =
        ShadowLog.getLogsForTag("WarpLink").filter { it.type == Log.WARN }.map { it.msg }

    // conformance: WL-S12
    @Test
    fun `a rejected key warns to Logcat with debug logging off`() {
        var verdict: Boolean? = null

        performServerValidation(
            storage = storage,
            apiClient = validating(Result.success(ValidateResponse(valid = false, domains = emptyList()))),
            // null logger is the debugLogging-off case: warn() must still fire.
            logger = Logger(debugEnabled = false),
            onResult = { verdict = it }
        )

        assertEquals(false, verdict)
        assertTrue(
            warnings().any { it.contains("rejected by the server") },
            "a server rejection must be visible in Logcat, got: ${warnings()}"
        )
    }

    // conformance: WL-S12
    @Test
    fun `an accepted key does not warn`() {
        // The control. Without it a warn() on every path would look correct.
        performServerValidation(
            storage = storage,
            apiClient = validating(Result.success(ValidateResponse(valid = true, domains = emptyList()))),
            logger = Logger(debugEnabled = false),
            onResult = {}
        )

        assertTrue(warnings().isEmpty(), "a valid key must not warn, got: ${warnings()}")
    }
}
