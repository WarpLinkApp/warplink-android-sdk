package app.warplink.internal

import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import org.json.JSONObject

/**
 * JSON (de)serialization for the cached deferred attribution result. Kept
 * separate from [Storage] so persistence and wire encoding stay one concern each.
 */
internal fun serializeDeepLink(deepLink: WarpLinkDeepLink): JSONObject {
    val json = JSONObject()
    json.put("linkId", deepLink.linkId)
    json.put("destination", deepLink.destination)
    json.put("deepLinkUrl", deepLink.deepLinkUrl ?: JSONObject.NULL)
    json.put("isDeferred", deepLink.isDeferred)
    json.put("matchType", deepLink.matchType?.name?.lowercase() ?: JSONObject.NULL)
    json.put("matchConfidence", deepLink.matchConfidence ?: JSONObject.NULL)
    json.put("matchGuaranteed", deepLink.matchGuaranteed)
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
        matchGuaranteed = json.optBoolean("matchGuaranteed", false),
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
