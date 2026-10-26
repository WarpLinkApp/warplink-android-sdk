package app.warplink.internal.referral

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One small JSON record in a file under `noBackupFilesDir`.
 *
 * The directory is the point, as for [app.warplink.internal.InstallMarkerStore]:
 * Auto Backup never restores it, so a record written here dies with the
 * install that wrote it and a reinstall never replays an old invitation.
 *
 * A write goes to a temporary file first and is renamed over the target, so a
 * kill mid-write leaves the old record whole rather than half of a new one.
 * An unreadable record reads as absent and is removed.
 */
internal class InstallJsonFile(directory: File, name: String) {

    private val target = File(directory, name)
    private val temp = File(directory, "$name.tmp")

    fun read(): JSONObject? {
        if (!target.exists()) return null
        return try {
            JSONObject(target.readText(Charsets.UTF_8))
        } catch (_: JSONException) {
            delete()
            null
        } catch (_: IOException) {
            null
        }
    }

    /** True when the record is now on disk. */
    fun write(record: JSONObject): Boolean = try {
        temp.writeText(record.toString(), Charsets.UTF_8)
        temp.renameTo(target) || run {
            target.delete()
            temp.renameTo(target)
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun delete() {
        try {
            target.delete()
            temp.delete()
        } catch (_: SecurityException) {
            // A locked-down container cannot delete: the record then stays,
            // and its own expiry and attempt count still bound it.
        }
    }
}
