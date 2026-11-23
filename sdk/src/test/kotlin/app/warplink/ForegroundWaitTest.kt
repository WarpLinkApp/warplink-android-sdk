package app.warplink

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WL-S71: the deferred check, automatic or manual, starts only while an
 * activity is on screen. A call made earlier waits and then runs, and is never
 * failed for waiting.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundWaitTest {

    private val key = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val api = LoopbackJsonServer("""{"matched":false}""")

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        // A fresh validation is cached, so the only request the stub sees is the match.
        Storage(context).apply {
            clearAll()
            apiKeyValidatedAt = System.currentTimeMillis()
        }
        putProcessInBackground()
    }

    @After
    fun tearDown() {
        api.close()
        WarpLink.reset()
    }

    @Test
    fun `WL-S71 a manual check called from the background sends nothing until an activity starts`() {
        api.serveOnce()
        configure(automatic = false)
        var answer: Result<WarpLinkDeepLink?>? = null

        WarpLink.checkDeferredDeepLink { answer = it }
        // An absence can only be watched for: a check that was not held would be on the wire by now.
        watchMainLooperFor()

        assertTrue(api.requestLines.isEmpty(), "no request while the app is in the background")
        assertNull(answer, "the call waits, it does not fail")

        startActivity()
        idleMainLooperUntil(message = "the waiting check never ran") { answer != null }

        assertEquals(1, api.requestLines.size)
        assertTrue(answer!!.isSuccess, "got ${answer!!.exceptionOrNull()}")
    }

    @Test
    fun `WL-S71 the automatic check waits for the first activity too`() {
        api.serveOnce()
        configure(automatic = true)
        watchMainLooperFor()

        assertTrue(api.requestLines.isEmpty(), "configure runs in Application.onCreate, before any activity")
        assertTrue(WarpLink.isConfigured)

        startActivity()
        pollForRequest()

        assertEquals(1, api.requestLines.size)
    }

    @Test
    fun `WL-S71 a check called while an activity is on screen runs at once`() {
        api.serveOnce()
        configure(automatic = false)
        startActivity()
        var answer: Result<WarpLinkDeepLink?>? = null

        WarpLink.checkDeferredDeepLink { answer = it }
        idleMainLooperUntil(message = "the check never answered") { answer != null }

        assertEquals(1, api.requestLines.size)
    }

    @Test
    fun `WL-S71 a completed check answers from the cache in the background`() {
        configure(automatic = false)
        Storage(context).apply {
            cachedAttribution = WarpLinkDeepLink(linkId = "lnk_1", destination = "https://example.com/a", isDeferred = true)
            deferredCheckCompleted = true
        }
        var answer: Result<WarpLinkDeepLink?>? = null

        WarpLink.checkDeferredDeepLink { answer = it }

        assertEquals("lnk_1", answer?.getOrNull()?.linkId)
        assertTrue(api.requestLines.isEmpty())
    }

    @Test
    fun `WL-S71 a call before configure still fails with not configured`() {
        var answer: Result<WarpLinkDeepLink?>? = null

        WarpLink.checkDeferredDeepLink { answer = it }

        assertEquals(WarpLinkError.NotConfigured, answer?.exceptionOrNull())
    }

    @Test
    fun `WL-S71 a waiting call runs on the session that is current when the app comes up`() {
        api.serveOnce()
        configure(automatic = false)
        var answer: Result<WarpLinkDeepLink?>? = null
        WarpLink.checkDeferredDeepLink { answer = it }

        configure(automatic = false, debug = true)
        startActivity()
        idleMainLooperUntil(message = "the waiting check never ran") { answer != null }

        assertEquals(1, api.requestLines.size)
        assertTrue(answer!!.isSuccess)
    }

    private fun configure(automatic: Boolean, debug: Boolean = false) {
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl, debugLogging = debug,
                automaticDeepLinks = false, automaticDeferredDeepLinks = automatic
            )
        )
    }

    private fun startActivity() {
        Robolectric.buildActivity(Activity::class.java).create().start()
    }

    private fun pollForRequest() {
        idleMainLooperUntil(message = "the request never reached the wire") { api.requestLines.isNotEmpty() }
    }

    /** The process the SDK sees is cached: no activity on screen, as when a push starts the app. */
    private fun putProcessInBackground() {
        val info = ActivityManager.RunningAppProcessInfo().apply {
            pid = android.os.Process.myPid()
            processName = context.packageName
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        }
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Shadows.shadowOf(manager).setProcesses(listOf(info))
    }
}
