package app.warplink.internal

import android.content.Context
import android.content.SharedPreferences
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import org.json.JSONObject

internal class Storage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var apiKeyValidatedAt: Long?
        get() {
            val value = prefs.getLong(KEY_API_KEY_VALIDATED_AT, -1L)
            return if (value == -1L) null else value
        }
        set(value) {
            if (value != null) {
                prefs.edit().putLong(KEY_API_KEY_VALIDATED_AT, value).apply()
            } else {
                prefs.edit().remove(KEY_API_KEY_VALIDATED_AT).apply()
            }
        }

    val isApiKeyValidationCacheValid: Boolean
        get() {
            val validatedAt = apiKeyValidatedAt ?: return false
            val elapsed = System.currentTimeMillis() - validatedAt
            return elapsed < VALIDATION_CACHE_DURATION_MS
        }

    var cachedAttribution: WarpLinkDeepLink?
        get() = readCachedAttribution()
        set(value) = writeCachedAttribution(value)

    fun clearCachedAttribution() {
        prefs.edit().remove(KEY_CACHED_ATTRIBUTION).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun readCachedAttribution(): WarpLinkDeepLink? {
        val json = prefs.getString(KEY_CACHED_ATTRIBUTION, null)
            ?: return null
        return try {
            deserializeDeepLink(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCachedAttribution(value: WarpLinkDeepLink?) {
        if (value == null) {
            prefs.edit().remove(KEY_CACHED_ATTRIBUTION).apply()
            return
        }
        val json = serializeDeepLink(value)
        prefs.edit().putString(KEY_CACHED_ATTRIBUTION, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "warplink_prefs"
        private const val KEY_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_API_KEY_VALIDATED_AT = "api_key_validated_at"
        private const val KEY_CACHED_ATTRIBUTION = "cached_attribution"
        private const val VALIDATION_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}

internal fun serializeDeepLink(deepLink: WarpLinkDeepLink): JSONObject {
    val json = JSONObject()
    json.put("linkId", deepLink.linkId)
    json.put("destination", deepLink.destination)
    json.put("deepLinkUrl", deepLink.deepLinkUrl ?: JSONObject.NULL)
    json.put("isDeferred", deepLink.isDeferred)
    json.put("matchType", deepLink.matchType?.name?.lowercase() ?: JSONObject.NULL)
    json.put("matchConfidence", deepLink.matchConfidence ?: JSONObject.NULL)
    json.put("customParams", JSONObject(deepLink.customParams))
    return json
}

internal fun deserializeDeepLink(json: JSONObject): WarpLinkDeepLink {
    val matchTypeStr = if (json.isNull("matchType")) null
        else json.getString("matchType")
    val matchType = when (matchTypeStr?.uppercase()) {
        "DETERMINISTIC" -> MatchType.DETERMINISTIC
        "PROBABILISTIC" -> MatchType.PROBABILISTIC
        else -> null
    }
    val matchConfidence = if (json.isNull("matchConfidence")) null
        else json.optDouble("matchConfidence").let { if (it.isNaN()) null else it }
    val customParams = parseCustomParams(json.optJSONObject("customParams"))

    return WarpLinkDeepLink(
        linkId = json.getString("linkId"),
        destination = json.getString("destination"),
        deepLinkUrl = if (json.isNull("deepLinkUrl")) null
            else json.getString("deepLinkUrl"),
        isDeferred = json.getBoolean("isDeferred"),
        matchType = matchType,
        matchConfidence = matchConfidence,
        customParams = customParams
    )
}

private fun parseCustomParams(json: JSONObject?): Map<String, Any> {
    if (json == null) return emptyMap()
    val map = mutableMapOf<String, Any>()
    for (key in json.keys()) {
        val value = json.get(key)
        if (value != JSONObject.NULL) {
            map[key] = value
        }
    }
    return map
}
