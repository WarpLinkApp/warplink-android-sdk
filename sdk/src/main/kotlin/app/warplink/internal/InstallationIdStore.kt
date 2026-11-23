package app.warplink.internal

import app.warplink.internal.referral.InstallJsonFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A random id made once per install and sent as `installation_id`.
 *
 * It lives under `noBackupFilesDir`, so Auto Backup never restores it and a
 * reinstall makes a new one. When the file cannot be written the id still holds
 * for the life of the process.
 */
internal class InstallationIdStore(directory: File, name: String) {

    private val file = InstallJsonFile(directory, name)
    private val lock = Any()
    private var cached: String? = null

    val id: String
        get() = synchronized(lock) {
            cached ?: (readStored() ?: UUID.randomUUID().toString().also(::store)).also { cached = it }
        }

    /** True when the id is gone from disk too. The next read makes a new one. */
    fun clear(): Boolean = synchronized(lock) {
        cached = null
        file.delete()
    }

    private fun readStored(): String? =
        file.read()?.optString(KEY)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }

    private fun store(id: String) {
        file.write(JSONObject().put(KEY, id))
    }

    private companion object {
        const val KEY = "installation_id"
    }
}
