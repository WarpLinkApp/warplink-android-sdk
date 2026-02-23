package app.warplink

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CheckDeferredDeepLinkTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun testNotConfiguredReturnsError() {
        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NotConfigured>(result!!.exceptionOrNull())
    }

    @Test
    fun testFirstLaunchNetworkErrorPropagates() {
        configureWithUnreachableEndpoint()

        var result: Result<WarpLinkDeepLink?>? = null
        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { r ->
            result = r
            latch.countDown()
        }

        idleLooperUntilLatch(latch)

        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun testFirstLaunchMarksIsFirstLaunchFalse() {
        val prefs = context.getSharedPreferences(
            "warplink_prefs", Context.MODE_PRIVATE
        )
        assertTrue(prefs.getBoolean("is_first_launch", true))

        configureWithUnreachableEndpoint()

        val latch = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { latch.countDown() }

        idleLooperUntilLatch(latch)

        assertFalse(prefs.getBoolean("is_first_launch", true))
    }

    @Test
    fun testNotFirstLaunchReturnsCachedMatch() {
        writeCachedAttribution(CACHED_ATTRIBUTION_JSON)
        setNotFirstLaunch()
        configureWithUnreachableEndpoint()

        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }

        assertTrue(result!!.isSuccess)
        val deepLink = result!!.getOrNull()!!
        assertEquals("link_cached", deepLink.linkId)
        assertEquals("https://example.com/cached", deepLink.destination)
        assertEquals("myapp://cached", deepLink.deepLinkUrl)
        assertEquals(mapOf("source" to "test"), deepLink.customParams)
        assertTrue(deepLink.isDeferred)
        assertEquals(MatchType.PROBABILISTIC, deepLink.matchType)
        assertEquals(0.85, deepLink.matchConfidence)
    }

    @Test
    fun testNotFirstLaunchReturnsNullWhenNoCache() {
        setNotFirstLaunch()
        configureWithUnreachableEndpoint()

        var result: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result = r }

        assertTrue(result!!.isSuccess)
        assertNull(result!!.getOrNull())
    }

    @Test
    fun testCachedResultSurvivesReread() {
        writeCachedAttribution(CACHED_ATTRIBUTION_JSON)
        setNotFirstLaunch()
        configureWithUnreachableEndpoint()

        var result1: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result1 = r }

        var result2: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { r -> result2 = r }

        val dl1 = result1!!.getOrNull()!!
        val dl2 = result2!!.getOrNull()!!
        assertEquals(dl1.linkId, dl2.linkId)
        assertEquals(dl1.destination, dl2.destination)
        assertEquals(dl1.deepLinkUrl, dl2.deepLinkUrl)
        assertEquals(dl1.matchType, dl2.matchType)
        assertEquals(dl1.matchConfidence, dl2.matchConfidence)
    }

    private fun configureWithUnreachableEndpoint() {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(apiEndpoint = "http://localhost:1")
        )
    }

    private fun setNotFirstLaunch() {
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_first_launch", false).commit()
    }

    private fun idleLooperUntilLatch(
        latch: CountDownLatch,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val looper = Shadows.shadowOf(Looper.getMainLooper())
        while (latch.count > 0 &&
            System.currentTimeMillis() < deadline
        ) {
            looper.idleFor(Duration.ofMillis(500))
            latch.await(50, TimeUnit.MILLISECONDS)
        }
        looper.idle()
    }

    private fun writeCachedAttribution(json: String) {
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().putString("cached_attribution", json).commit()
    }

    companion object {
        private val CACHED_ATTRIBUTION_JSON = """
            {
                "linkId": "link_cached",
                "destination": "https://example.com/cached",
                "deepLinkUrl": "myapp://cached",
                "customParams": {"source": "test"},
                "isDeferred": true,
                "matchType": "probabilistic",
                "matchConfidence": 0.85
            }
        """.trimIndent()
    }
}
