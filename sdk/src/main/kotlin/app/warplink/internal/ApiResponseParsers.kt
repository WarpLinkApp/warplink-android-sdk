package app.warplink.internal

import app.warplink.internal.referral.ReferralEvidenceFields
import org.json.JSONObject

internal fun parseLink(body: String): LinkResponse {
    val json = JSONObject(body)
    return LinkResponse(
        id = json.getString("id"),
        slug = json.getString("slug"),
        destinationUrl = json.getString("destination_url"),
        iosUrl = json.optStringOrNull("ios_url"),
        androidUrl = json.optStringOrNull("android_url"),
        customParams = parseCustomParams(json.optJSONObject("custom_params"))
    )
}

internal fun parseValidate(body: String): ValidateResponse {
    val json = JSONObject(body)
    val domainsArray = json.optJSONArray("domains")
    val domains = if (domainsArray == null) {
        emptyList()
    } else {
        (0 until domainsArray.length())
            .mapNotNull { domainsArray.optStringOrNull(it) }
    }
    return ValidateResponse(
        valid = json.optBoolean("valid", true),
        domains = domains,
        policy = SdkPolicy.fromJson(json.optJSONObject("sdk_policy"))
    )
}

internal fun parseAttribution(body: String): AttributionResponse {
    val json = JSONObject(body)
    return AttributionResponse(
        matched = json.optBoolean("matched", false),
        matchType = json.optStringOrNull("match_type"),
        matchConfidence = json.optDoubleOrNull("match_confidence"),
        matchGuaranteed = json.optBoolean("match_guaranteed", false),
        linkId = json.optStringOrNull("link_id"),
        deepLinkUrl = json.optStringOrNull("deep_link_url"),
        destinationUrl = json.optStringOrNull("destination_url"),
        customParams = json.optJSONObject("custom_params")
            ?.let { parseCustomParams(it) },
        installId = json.optStringOrNull("install_id"),
        evidence = ReferralEvidenceFields.fromJson(json),
        tether = TetherResponse.fromJson(json.optJSONObject("tether")),
        policy = SdkPolicy.fromJson(json.optJSONObject("sdk_policy"))
    )
}

private fun parseCustomParams(json: JSONObject?): Map<String, Any> {
    if (json == null) return emptyMap()
    val map = mutableMapOf<String, Any>()
    for (key in json.keys()) {
        map[key] = json.get(key)
    }
    return map
}
