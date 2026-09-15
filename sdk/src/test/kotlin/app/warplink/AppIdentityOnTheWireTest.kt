package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.Storage
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * WarpLink.configure hands appContext.packageName to ApiClient. Nothing pinned
 * that argument: dropping it compiled, stayed green, and left multi-app orgs
 * with ambiguous_app or mis-filed installs.
 */
@RunWith(RobolectricTestRunner::class)
class AppIdentityOnTheWireTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private lateinit var stub: LoopbackJsonServer

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
        stub = LoopbackJsonServer("""{"valid":true,"matched":false}""")
    }

    @After
    fun tearDown() {
        stub.close()
        WarpLink.reset()
    }

    @Test
    fun `configure hands the package name to the attribution request`() {
        // configure() fires /sdk/validate and the check fires /attribution/match;
        // both go to the stub, in either order, so serve two.
        stub.serveOnce()
        stub.serveOnce()
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = stub.baseUrl,
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = false
            )
        )
        val done = CountDownLatch(1)
        WarpLink.checkDeferredDeepLink { done.countDown() }
        idleMainLooperUntil(done)

        val attribution = stub.requestBodies.firstOrNull { it.contains("\"platform\"") }
        assertNotNull(attribution, "no attribution request reached the stub: ${stub.requestBodies}")
        assertEquals(context.packageName, JSONObject(attribution).getString("app_package_name"))
    }
}
