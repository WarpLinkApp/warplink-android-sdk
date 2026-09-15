package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.LoopbackJsonServer
import app.warplink.WarpLinkDeepLink
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
import kotlin.test.assertTrue

/**
 * End-to-end cover for the `is_reinstall` tag: from the markers on disk,
 * through the deferred check, to the bytes on the wire.
 *
 * The unit tests on either side of this one can both pass while the value is
 * never plumbed between them, which is exactly how a hand-ported SDK drifts.
 */
@RunWith(RobolectricTestRunner::class)
class ReinstallAttributionTest {

    private lateinit var context: Context
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
    }

    @Test
    fun `a first install reports is_reinstall false`() {
        val body = runCheckAgainstStub(Storage(context))

        assertFalse(body.getBoolean("is_reinstall"))
    }

    // Also WL-S24. This is the Android probabilistic half of the wire flag:
    // collectAndMatch builds the body, where the WL-S24-named test below covers
    // the referrer path. The checker collects markers per test tree, not per
    // test, so the second id enforces nothing extra; it is here so a reader can
    // see both contracts this test serves.
    @Test
    fun `WL-S16 a reinstall carrying restored SharedPreferences reports true`() {
        val first = runCheckAgainstStub(Storage(context))
        assertFalse(first.getBoolean("is_reinstall"))

        simulateReinstall()

        // A reinstall counts as an install: the check runs again, and this time
        // it says so. Both halves matter, so assert the request happened at all.
        val second = runCheckAgainstStub(Storage(context))
        assertTrue(second.getBoolean("is_reinstall"))
    }

    @Test
    fun `WL-S24 the deterministic referrer path carries the tag too`() {
        runCheckAgainstStub(Storage(context))
        simulateReinstall()

        // The referrer path builds its body through the same function but is a
        // different call, so it has to be proven separately. A returning user
        // who reinstalls from a Play Store link takes exactly this path.
        val body = runCheckAgainstStub(
            Storage(context),
            referrer = ReferrerSource { it(Result.success(ReferrerRead(LINK_ID, 0L))) }
        )

        assertEquals(LINK_ID, body.getString("referrer"))
        assertTrue(body.getBoolean("is_reinstall"))
    }

    @Test
    fun `the gate still blocks a second check within one install`() {
        runCheckAgainstStub(Storage(context))

        val stub = stub()
        stub.serveOnce()
        var result: Result<WarpLinkDeepLink?>? = null
        performDeferredCheck(
            Storage(context), FingerprintCollector(),
            ApiClient(API_KEY, stub.baseUrl), null, null
        ) { result = it }

        // Same install, gate intact: nothing may leave the device, tagged or
        // otherwise. Being seen before must never re-open the gate.
        assertFalse(stub.requestReceived.await(1, TimeUnit.SECONDS))
        assertTrue(result!!.isSuccess)
    }

    /** Runs one full deferred check against a stub that answers "no match". */
    private fun runCheckAgainstStub(
        storage: Storage,
        referrer: ReferrerSource? = null
    ): JSONObject {
        val stub = stub()
        stub.serveOnce()
        val done = CountDownLatch(1)
        performDeferredCheck(
            storage, FingerprintCollector(),
            ApiClient(API_KEY, stub.baseUrl), referrer, null
        ) { done.countDown() }
        idleMainLooperUntil(done)

        assertTrue(storage.deferredCheckCompleted, "the check never completed")
        return JSONObject(stub.requestBody)
    }

    private fun stub(): LoopbackJsonServer =
        LoopbackJsonServer(NO_MATCH_JSON).also { stubs.add(it) }

    /**
     * Deleting the app wipes `noBackupFilesDir`; Android Auto Backup then
     * restores SharedPreferences onto the new install.
     */
    private fun simulateReinstall() {
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
        const val NO_MATCH_JSON = """{"matched":false}"""
        const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
