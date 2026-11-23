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
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals

/** WL-S74: `WarpLink.deferredCheckStatus` on the public object, through a real session. */
@RunWith(RobolectricTestRunner::class)
class DeferredCheckStatusApiTest {

    private val key = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val api = LoopbackJsonServer("""{"matched":false}""")

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
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
    fun `WL-S74 before configure the status is NotChecked`() {
        assertEquals(DeferredCheckStatus.NotChecked, WarpLink.deferredCheckStatus)
    }

    @Test
    fun `WL-S74 after configure with no check yet the status is NotChecked`() {
        configure()

        assertEquals(DeferredCheckStatus.NotChecked, WarpLink.deferredCheckStatus)
    }

    @Test
    fun `WL-S74 a check waiting for the app to come up reads Checking`() {
        configure()
        WarpLink.checkDeferredDeepLink { }

        assertEquals(DeferredCheckStatus.Checking, WarpLink.deferredCheckStatus)
    }

    @Test
    fun `WL-S74 a check on the wire reads Checking, then NoMatch once the answer is applied`() {
        val hold = CountDownLatch(1)
        api.serveOnce(hold)
        configure()
        Robolectric.buildActivity(Activity::class.java).create().start()
        var answered = false
        WarpLink.checkDeferredDeepLink { answered = true }
        idleMainLooperUntil(message = "the request never reached the wire") { api.requestReceived.count == 0L }

        assertEquals(DeferredCheckStatus.Checking, WarpLink.deferredCheckStatus)

        hold.countDown()
        idleMainLooperUntil(message = "the check never answered") { answered }
        assertEquals(DeferredCheckStatus.NoMatch, WarpLink.deferredCheckStatus)
    }

    @Test
    fun `WL-S74 the status reads the stored gate of the install`() {
        Storage(context).apply {
            cachedAttribution = WarpLinkDeepLink(linkId = "lnk_1", destination = "https://example.com/a", isDeferred = true)
            deferredCheckCompleted = true
        }
        configure()

        assertEquals(DeferredCheckStatus.Matched, WarpLink.deferredCheckStatus)
    }

    private fun configure() {
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl,
                automaticDeepLinks = false, automaticDeferredDeepLinks = false
            )
        )
    }

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
