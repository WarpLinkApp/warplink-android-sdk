package app.warplink

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.KeyEnvironment
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import app.warplink.internal.UriParser
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `clearAttribution` is a Test-key tool: it resets what a first-install test
 * leaves behind, and it can never touch a live install's attribution.
 */
@RunWith(RobolectricTestRunner::class)
class ClearAttributionTest {

    private val testKey = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    private val liveKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private lateinit var testStorage: Storage
    private lateinit var liveStorage: Storage
    private val api = LoopbackJsonServer(MATCH_JSON)
    private val releaseResponse = CountDownLatch(1)

    @Before
    fun setUp() {
        WarpLink.reset()
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        context = ApplicationProvider.getApplicationContext()
        testStorage = Storage(context, KeyEnvironment.TEST).also { it.clearAll() }
        liveStorage = Storage(context, KeyEnvironment.PRODUCTION).also { it.clearAll() }
    }

    @After
    fun tearDown() {
        releaseResponse.countDown()
        api.close()
        WarpLink.reset()
    }

    @Test
    fun `before configure it fails with NotConfigured`() {
        val result = WarpLink.clearAttribution()

        assertIs<WarpLinkError.NotConfigured>(result.exceptionOrNull())
    }

    @Test
    fun `WL-S52 a marker that cannot be deleted fails with DecodingError`() {
        testStorage.deferredCheckCompleted = true
        configure(testKey)
        val marker = File(context.noBackupFilesDir, "warplink_deferred_completed_test")
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        assertTrue(File(marker, "pin").createNewFile())

        val result = WarpLink.clearAttribution()

        val error = assertIs<WarpLinkError.DecodingError>(result.exceptionOrNull())
        assertEquals("Could not clear stored attribution data.", error.cause?.message)
    }

    @Test
    fun `WL-S51 a live key makes it a no-op that succeeds`() {
        liveStorage.deferredCheckCompleted = true
        liveStorage.deferredCheckAttempted = true
        liveStorage.cachedAttribution = LINK
        configure(liveKey)

        val result = WarpLink.clearAttribution()

        assertTrue(result.isSuccess)
        assertTrue(liveStorage.deferredCheckCompleted)
        assertTrue(liveStorage.deferredCheckAttempted)
        assertEquals("lnk_1", Storage(context).cachedAttribution?.linkId)
    }

    @Test
    fun `WL-S50 a Test key clears the whole set and the next check is a fresh attempt`() {
        testStorage.deferredCheckCompleted = true
        testStorage.deferredCheckAttempted = true
        testStorage.cachedAttribution = LINK
        liveStorage.deferredCheckCompleted = true
        liveStorage.cachedAttribution = LINK
        configure(testKey)
        api.serveOnce()

        assertTrue(WarpLink.clearAttribution().isSuccess)

        assertFalse(WarpLink.isAttributionComplete())
        assertNull(WarpLink.attributionResult)
        assertFalse(Storage(context, KeyEnvironment.TEST).deferredCheckAttempted)
        val fresh = CountDownLatch(1)
        var answer: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { answer = it; fresh.countDown() }
        idleMainLooperUntil(fresh)
        assertEquals("lnk_1", answer?.getOrNull()?.linkId)
        assertTrue(liveStorage.deferredCheckCompleted)
        assertEquals("lnk_1", liveStorage.cachedAttribution?.linkId)
    }

    @Test
    fun `with automatic deferred links on, clearing starts a fresh check`() {
        testStorage.deferredCheckCompleted = true
        configure(testKey, automaticDeferredDeepLinks = true)
        api.serveOnce()

        WarpLink.clearAttribution()

        awaitRequest("no fresh check was started")
    }

    @Test
    fun `callers parked on the abandoned check are answered with success(null)`() {
        configure(testKey)
        api.serveOnce(gate = releaseResponse)
        val first = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { first.countDown() }
        awaitRequest("the check never reached the wire")
        var parked: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { parked = it }

        WarpLink.clearAttribution()

        assertTrue(parked!!.isSuccess)
        assertNull(parked!!.getOrNull())
    }

    @Test
    fun `a late answer from before the clear cannot restore the cleared state`() {
        configure(testKey)
        api.serveOnce(gate = releaseResponse)
        val late = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { late.countDown() }
        awaitRequest("the check never reached the wire")

        WarpLink.clearAttribution()
        releaseResponse.countDown()
        idleMainLooperUntil(late)

        val after = Storage(context, KeyEnvironment.TEST)
        assertFalse(after.deferredCheckCompleted)
        assertNull(after.cachedAttribution)
        assertFalse(after.deferredCheckAttempted)
    }

    @Test
    fun `switching the key environment does not leak cached attribution or link domains`() {
        testStorage.cachedAttribution = LINK
        testStorage.cachedDomains = listOf("test.example.com")
        val testLink = Uri.parse("https://test.example.com/abc")

        configure(testKey)
        assertEquals("lnk_1", WarpLink.attributionResult?.linkId)
        assertTrue(WarpLink.isWarpLinkUri(testLink))

        configure(liveKey)
        assertNull(WarpLink.attributionResult)
        assertFalse(WarpLink.isWarpLinkUri(testLink))

        configure(testKey)
        assertEquals("lnk_1", WarpLink.attributionResult?.linkId)
        assertTrue(WarpLink.isWarpLinkUri(testLink))
        UriParser.resetKnownDomains()
    }

    private fun awaitRequest(message: String) =
        idleMainLooperUntil(message = message) { api.requestReceived.count == 0L }

    /** Configures with a fresh key validation cached, so the one stub answer is for the check. */
    private fun configure(key: String, automaticDeferredDeepLinks: Boolean = false) {
        Storage(context, KeyEnvironment.of(key)).apiKeyValidatedAt = System.currentTimeMillis()
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl,
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = automaticDeferredDeepLinks,
            )
        )
    }

    private companion object {
        const val MATCH_JSON =
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"lnk_1","destination_url":"https://example.com/promo"}"""
        val LINK = WarpLinkDeepLink(linkId = "lnk_1", destination = "https://example.com/promo", isDeferred = true)
    }
}
