package app.warplink.internal.referral

import java.net.URLDecoder

/**
 * Parses a Play install referrer string into its key/value pairs.
 *
 * The string is split into pairs first and each key and value is decoded
 * after, so an encoded `&` or `=` inside a customer's own value cannot forge a
 * pair. The first occurrence of a key wins: the Worker puts WarpLink's pairs
 * ahead of any referrer the customer already had, and this keeps them readable
 * while the customer's pairs survive for their own tooling.
 *
 * A string that arrives with its separators still percent-encoded is decoded
 * once as a whole, then read the same way. Pairs with an empty key, no `=`, or
 * a malformed escape are skipped.
 */
internal fun parseReferrerQuery(referrer: String): Map<String, String> {
    if (referrer.isBlank()) return emptyMap()
    if (!referrer.contains('=') && referrer.contains("%3D", ignoreCase = true)) {
        val decoded = decodeOrNull(referrer) ?: return emptyMap()
        return parseReferrerPairs(decoded)
    }
    return parseReferrerPairs(referrer)
}

private fun parseReferrerPairs(query: String): Map<String, String> {
    val pairs = linkedMapOf<String, String>()
    for (pair in query.split("&")) {
        val parts = pair.split("=", limit = 2)
        if (parts.size != 2) continue
        val key = decodeOrNull(parts[0])?.takeIf { it.isNotEmpty() } ?: continue
        val value = decodeOrNull(parts[1]) ?: continue
        pairs.putIfAbsent(key, value)
    }
    return pairs
}

private fun decodeOrNull(text: String): String? =
    try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
