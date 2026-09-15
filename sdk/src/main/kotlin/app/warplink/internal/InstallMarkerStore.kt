package app.warplink.internal

import android.content.Context
import java.io.File

/**
 * Boolean markers scoped to ONE install, stored as empty files under
 * `noBackupFilesDir`.
 *
 * The directory is the whole point. Android Auto Backup restores
 * SharedPreferences onto a reinstall, but it never restores no-backup files, so
 * a marker written here dies with the install that wrote it. That is exactly
 * what a "has this install already done X" gate needs, and it is why the gate
 * cannot also answer "have we ever seen this device": anything that must
 * OUTLIVE an uninstall belongs in SharedPreferences instead (see
 * [Storage.deviceHasCompletedAttribution]). A write reports whether it
 * succeeded, so a caller can fall back to another store when it did not.
 */
internal class InstallMarkerStore(context: Context) {

    private val dir: File = context.noBackupFilesDir

    fun isSet(name: String): Boolean = file(name).exists()

    /** True when the store now holds the requested state. */
    fun set(name: String, present: Boolean): Boolean =
        if (present) write(name) else delete(name)

    fun clear(vararg names: String) {
        names.forEach { file(it).delete() }
    }

    private fun write(name: String): Boolean = try {
        val target = file(name)
        target.exists() || target.createNewFile()
    } catch (_: Exception) {
        // Not IOException: File.createNewFile() can throw an unchecked
        // SecurityException on exactly the locked-down container this
        // fallback exists for (review A5 asks for the broad catch).
        // Reported, not swallowed: Storage mirrors the gate elsewhere when this
        // store cannot hold it. Silently returning re-ran the check, re-sent the
        // request and re-routed the user on every launch.
        false
    }

    private fun delete(name: String): Boolean = try {
        val target = file(name)
        target.delete() || !target.exists()
    } catch (_: Exception) {
        // Not IOException: File.delete() can throw an unchecked
        // SecurityException on exactly the locked-down container this
        // fallback exists for. Reported, not swallowed, matching write().
        false
    }

    private fun file(name: String): File = File(dir, name)
}
