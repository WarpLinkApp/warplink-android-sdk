package app.warplink

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device-seen marker outlives an uninstall only because Auto Backup
 * restores SharedPreferences. A host that turned backup off gets first-install
 * reports for every reinstall, and nothing told them why.
 */
@RunWith(RobolectricTestRunner::class)
class BackupCaveatLogTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private var originalFlags = 0

    @Before
    fun setUp() {
        WarpLink.reset()
        ShadowLog.clear()
        context = ApplicationProvider.getApplicationContext()
        originalFlags = context.applicationInfo.flags
    }

    @After
    fun tearDown() {
        context.applicationInfo.flags = originalFlags
        WarpLink.reset()
    }

    @Test
    fun `configure logs the reinstall caveat when Auto Backup is disabled`() {
        context.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_ALLOW_BACKUP.inv()

        configure()

        assertTrue(logs().any { it.contains("Auto Backup is disabled") }, "got: ${logs()}")
    }

    @Test
    fun `configure says nothing about backup when it is enabled`() {
        context.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_ALLOW_BACKUP

        configure()

        assertFalse(logs().any { it.contains("Auto Backup") }, "got: ${logs()}")
    }

    private fun configure() {
        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = "http://localhost:1",
                debugLogging = true,
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = false
            )
        )
    }

    private fun logs(): List<String> = ShadowLog.getLogsForTag("WarpLink").map { it.msg }
}
