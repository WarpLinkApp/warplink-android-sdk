package app.warplink.internal

import android.content.SharedPreferences

/** The key validation and the link domains `/sdk/validate` returned, cached in [prefs] for offline launches. */
internal class ValidationStore(private val prefs: SharedPreferences) {

    var validatedAt: Long?
        get() {
            val value = prefs.getLong(StorageKeys.API_KEY_VALIDATED_AT, -1L)
            return if (value == -1L) null else value
        }
        set(value) {
            val edit = prefs.edit()
            if (value != null) edit.putLong(StorageKeys.API_KEY_VALIDATED_AT, value) else edit.remove(StorageKeys.API_KEY_VALIDATED_AT)
            edit.apply()
        }

    val isCacheValid: Boolean
        get() {
            val validatedAt = validatedAt ?: return false
            return System.currentTimeMillis() - validatedAt < CACHE_DURATION_MS
        }

    var domains: List<String>
        get() = readDomains(prefs)
        set(value) = writeDomains(prefs, value)

    private companion object {
        const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
