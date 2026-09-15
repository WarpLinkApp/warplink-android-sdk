package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When noBackupFilesDir cannot take the gate, the gate has to hold somewhere,
 * or every launch re-runs the check and re-routes the user. It also has to
 * hold ONLY for this install: the only other store is SharedPreferences, which
 * Auto Backup restores onto the next install.
 */
@RunWith(RobolectricTestRunner::class)
class GateWriteFallbackTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        restoreMarkerDir()
        Storage(context).clearAll()
    }

    @After
    fun tearDown() {
        restoreMarkerDir()
    }

    @Test
    fun `WL-S25 a failed gate write still gates this install`() {
        breakMarkerDir()
        setInstallTimes(firstInstall = 1_000L, lastUpdate = 1_000L)

        Storage(context).deferredCheckCompleted = true

        // Sanity: the marker store really could not write.
        assertFalse(File(context.noBackupFilesDir, "warplink_deferred_completed").exists())
        // Every launch re-running the check re-sent the request and re-routed
        // the user into the campaign screen. The gate has to hold somewhere.
        assertTrue(Storage(context).deferredCheckCompleted)
    }

    @Test
    fun `WL-S25 the fallback never gates the next install`() {
        breakMarkerDir()
        setInstallTimes(firstInstall = 1_000L, lastUpdate = 1_000L)
        Storage(context).deferredCheckCompleted = true

        // Auto Backup restores SharedPreferences onto a reinstall, fallback
        // included. The new install has a new firstInstallTime, so the restored
        // value names a different install and must not close its gate.
        setInstallTimes(firstInstall = 9_000L, lastUpdate = 9_000L)
        assertFalse(Storage(context).deferredCheckCompleted)
    }

    @Test
    fun `no fallback is written while the marker store works`() {
        Storage(context).deferredCheckCompleted = true

        val prefs = context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("deferred_completed_for_install"))
    }

    @Test
    fun `clearing the gate clears the fallback too`() {
        breakMarkerDir()
        val storage = Storage(context)
        storage.deferredCheckCompleted = true
        storage.deferredCheckCompleted = false

        assertFalse(Storage(context).deferredCheckCompleted)
    }

    /** A regular FILE at the directory's path makes every createNewFile under it throw. */
    private fun breakMarkerDir() {
        val dir = context.noBackupFilesDir
        dir.deleteRecursively()
        dir.writeText("")
    }

    private fun restoreMarkerDir() {
        val dir = context.noBackupFilesDir
        if (dir.isFile) dir.delete()
        dir.mkdirs()
    }

    private fun setInstallTimes(firstInstall: Long, lastUpdate: Long) {
        val info = Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = firstInstall
        info.lastUpdateTime = lastUpdate
    }
}
