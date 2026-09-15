package app.warplink.internal

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every failure of the Play Install Referrer collapses into
 * `Result.success(null)`, which is the same value an organic install produces.
 * That is the return contract and it stays: the caller treats "no WarpLink
 * referrer" as a normal outcome, not an error.
 *
 * What must NOT be the same is the diagnosis. Referrer is Android's
 * deterministic attribution path, so a silent loss there downgrades every
 * install to the probabilistic tier with nothing to point at. A developer has
 * to be able to tell "this user installed organically" from "the referrer API
 * never worked here".
 *
 * The reader takes an injected client factory so these branches can be driven
 * without a Play Store. See [FakeInstallReferrerClient].
 */
@RunWith(RobolectricTestRunner::class)
class InstallReferrerDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowLog.clear()
    }

    /** Reads through the reader with [client], returning the logged lines. */
    private fun diagnose(client: InstallReferrerClientDouble): Pair<String?, List<String>> {
        val done = CountDownLatch(1)
        var linkId: String? = null

        InstallReferrerReader(context, Logger(debugEnabled = true)) { client.instance }
            .readReferrer { result ->
                linkId = result.getOrNull()?.linkId
                done.countDown()
            }

        app.warplink.idleMainLooperUntil(done)
        return linkId to ShadowLog.getLogs().map { it.msg }
    }

    @Test
    fun `WL-S18 every distinct failure names a distinct cause`() {
        val causes = FAILURES.map { (label, client) ->
            ShadowLog.clear()
            val (linkId, logs) = diagnose(client())
            // The return value is identical for all of them, by design.
            assertNull(linkId, "$label should still return null")
            val named = logs.filter { it.contains(UNAVAILABLE) }
            assertTrue(named.isNotEmpty(), "$label logged no cause, got: $logs")
            named.first()
        }

        // Distinct, so the log line alone tells a developer which one happened.
        assertEquals(
            FAILURES.size,
            causes.toSet().size,
            "two failures share a cause line: $causes"
        )
    }

    @Test
    fun `WL-S18 an organic install is not reported as a failure`() {
        val (linkId, logs) = diagnose(
            InstallReferrerClientDouble {
                FakeInstallReferrerClient.respondWith(
                    InstallReferrerResponseCodes.OK,
                    "utm_source=google-play&utm_medium=organic"
                )
            }
        )

        assertNull(linkId)
        // Same null as every failure above, but it must NOT be described as the
        // referrer being unavailable. That distinction is the whole point.
        assertTrue(
            logs.none { it.contains(UNAVAILABLE) },
            "an organic install was reported as a referrer failure: $logs"
        )
        assertTrue(logs.any { it.contains("no WarpLink link id") }, "got: $logs")
    }

    @Test
    fun `WL-S18 a matched referrer reports the link it found`() {
        val (linkId, logs) = diagnose(
            InstallReferrerClientDouble {
                FakeInstallReferrerClient.respondWith(
                    InstallReferrerResponseCodes.OK,
                    "utm_source=warplink&utm_content=$LINK_ID"
                )
            }
        )

        assertEquals(LINK_ID, linkId)
        assertTrue(logs.any { it.contains("matched link $LINK_ID") }, "got: $logs")
    }

    @Test
    fun `each Play response code has its own description`() {
        val codes = InstallReferrerResponseCodes.NON_OK

        val described = codes.map { InstallReferrerResponseCodes.describe(it) }

        assertEquals(codes.size, described.toSet().size)
        assertTrue(described.none { it.isBlank() })
        // The raw code travels with the description: it is what a developer
        // pastes into the Play documentation.
        codes.forEachIndexed { i, code ->
            assertTrue(described[i].contains(code.toString()))
        }
    }

    @Test
    fun `an unknown response code is still described rather than dropped`() {
        assertTrue(
            InstallReferrerResponseCodes.describe(UNKNOWN_CODE)
                .contains(UNKNOWN_CODE.toString())
        )
    }

    private companion object {
        const val UNKNOWN_CODE = 99
        const val UNAVAILABLE = "Install referrer unavailable"
        const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

        /** One entry per way the referrer can fail to produce a link id. */
        val FAILURES: List<Pair<String, () -> InstallReferrerClientDouble>> = listOf(
            "denied bind" to {
                InstallReferrerClientDouble {
                    FakeInstallReferrerClient.respondWith(
                        InstallReferrerResponseCodes.PERMISSION_ERROR
                    )
                }
            },
            "feature not supported" to {
                InstallReferrerClientDouble {
                    FakeInstallReferrerClient.respondWith(
                        InstallReferrerResponseCodes.FEATURE_NOT_SUPPORTED
                    )
                }
            },
            "service unavailable" to {
                InstallReferrerClientDouble {
                    FakeInstallReferrerClient.respondWith(
                        InstallReferrerResponseCodes.SERVICE_UNAVAILABLE
                    )
                }
            },
            "read threw" to { InstallReferrerClientDouble { FakeInstallReferrerClient.failingRead() } },
            "start threw" to { InstallReferrerClientDouble { FakeInstallReferrerClient.failingStart() } },
            "disconnected" to { InstallReferrerClientDouble { FakeInstallReferrerClient.disconnecting() } }
        )
    }
}

/** Defers construction so each case builds its own client. */
internal class InstallReferrerClientDouble(
    private val build: () -> FakeInstallReferrerClient
) {
    val instance: FakeInstallReferrerClient by lazy { build() }
}
