package app.warplink.internal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Null-safe reads for the AOSP `org.json` that ships inside Android.
 *
 * `optString` is not safe to use directly here. Android's implementation is
 * libcore's, not the json.org reference build, and for an explicit JSON `null`
 * it maps the `JSONObject.NULL` sentinel through `String.valueOf`, returning
 * the 4-character String "null" rather than the empty String. A non-empty
 * String survives `ifEmpty`, `?:` and `?.let` alike, so the literal text
 * reaches the host as if it were a real value.
 *
 * The API answers absent fields with an explicit `null` rather than omitting
 * the key (`android_url`, `deep_link_url`, `install_id` among others), so this
 * is the common response shape, not an edge case.
 */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).ifEmpty { null }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (isNull(key)) return null
    return optDouble(key).takeUnless { it.isNaN() }
}

internal fun JSONArray.optStringOrNull(index: Int): String? {
    if (isNull(index)) return null
    return optString(index).ifEmpty { null }
}
