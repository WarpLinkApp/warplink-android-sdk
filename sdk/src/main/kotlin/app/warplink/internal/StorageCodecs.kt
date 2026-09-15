package app.warplink.internal

import android.content.SharedPreferences
import app.warplink.WarpLinkDeepLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read/write codecs for the two JSON-backed [Storage] fields, split out purely
 * to keep `Storage.kt` under its line cap. These are not a general-purpose
 * abstraction: they take `prefs` directly rather than a `Storage` so they stay
 * simple free functions instead of a second class with its own lifetime.
 */
internal const val KEY_CACHED_DOMAINS = "cached_domains"
internal const val KEY_CACHED_ATTRIBUTION = "cached_attribution"

internal fun readDomains(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString(KEY_CACHED_DOMAINS, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length())
            .mapNotNull { array.optStringOrNull(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun writeDomains(prefs: SharedPreferences, value: List<String>) {
    val array = JSONArray()
    value.forEach { array.put(it) }
    prefs.edit().putString(KEY_CACHED_DOMAINS, array.toString()).apply()
}

internal fun readCachedAttribution(prefs: SharedPreferences): WarpLinkDeepLink? {
    val json = prefs.getString(KEY_CACHED_ATTRIBUTION, null)
        ?: return null
    return try {
        deserializeDeepLink(JSONObject(json))
    } catch (_: Exception) {
        null
    }
}

internal fun writeCachedAttribution(prefs: SharedPreferences, value: WarpLinkDeepLink?) {
    if (value == null) {
        prefs.edit().remove(KEY_CACHED_ATTRIBUTION).apply()
        return
    }
    val json = serializeDeepLink(value)
    prefs.edit().putString(KEY_CACHED_ATTRIBUTION, json.toString()).apply()
}
