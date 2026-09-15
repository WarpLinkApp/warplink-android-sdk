package app.warplink

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.AutoLinkHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Pins the cold-start registration lifecycle of [AutoLinkHandler].
 *
 * `WarpLink.configure` retires the previous handler with [AutoLinkHandler.unregister]
 * before installing a replacement, and it is the only thing that ever does so.
 * Every caller therefore assumes two invariants that the class does not enforce:
 * a retired handler never registers again, and registering twice never leaves a
 * callbacks object behind that `unregister` cannot reach.
 *
 * These are asserted through the real `ActivityLifecycleCallbacks` path rather
 * than through an internal flag, so a pass means a link really stopped arriving.
 */
@RunWith(RobolectricTestRunner::class)
class AutoLinkHandlerRegistrationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val uri = "https://aplnk.to/abc123"
    private var resolveCount = 0

    private fun handler() = AutoLinkHandler(
        application = app,
        logger = null,
        isWarpLinkUri = { true },
        onLink = {},
        resolve = { _, _ -> resolveCount++ }
    )

    private fun launchActivityWithLink() {
        Robolectric.buildActivity(
            Activity::class.java,
            Intent().apply { data = Uri.parse(uri) }
        ).create()
    }

    /**
     * Positive control. Without this, a passing red spec below could mean the
     * lifecycle callback never fires under Robolectric at all, and the other two
     * assertions would be vacuous.
     */
    @Test
    fun `a registered handler dispatches the launching activity's link`() {
        handler().registerColdStart()

        launchActivityWithLink()

        assertEquals(1, resolveCount)
    }

    @Test
    fun `WL-S23 a retired handler does not register cold-start handling again`() {
        val handler = handler()
        handler.registerColdStart()
        handler.unregister()

        // configure() never does this today, but nothing stops it: the handler
        // is read into a local before it is used, so a reconfigure in between
        // hands this call a handler that was already retired.
        handler.registerColdStart()

        launchActivityWithLink()

        assertEquals(0, resolveCount)
    }

    @Test
    fun `WL-S23 registering twice leaves nothing behind after unregister`() {
        val handler = handler()
        handler.registerColdStart()
        handler.registerColdStart()

        handler.unregister()

        launchActivityWithLink()

        assertEquals(0, resolveCount)
    }
}
