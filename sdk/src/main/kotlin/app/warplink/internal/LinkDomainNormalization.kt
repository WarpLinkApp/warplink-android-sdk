package app.warplink.internal

/**
 * Every code point stripped from the ends of a declared domain, listed
 * explicitly. Kotlin's trim() and Swift's whitespacesAndNewlines disagree:
 * Kotlin strips U+001C to U+001F where Swift keeps them, and Swift strips U+0085 and
 * U+200B where Kotlin keeps them. A domain pasted with a zero-width space on its end
 * therefore matched on one platform and never on the other. The same list,
 * character for character, lives in LinkDomainNormalizer.swift.
 */
private val TRIM_SET: Set<Char> = buildSet {
    ('\u0009'..'\u000D').forEach { add(it) }
    add(' '); add('\u0085'); add('\u00A0'); add('\u1680')
    ('\u2000'..'\u200A').forEach { add(it) }
    add('\u2028'); add('\u2029'); add('\u202F'); add('\u205F'); add('\u3000')
    // Invisible formatting characters a paste can carry.
    add('\u200B'); add('\u200C'); add('\u200D'); add('\u2060'); add('\uFEFF')
}

/**
 * Reduce one developer-supplied link domain to the bare host an incoming
 * intent carries, or null when nothing usable is left.
 *
 * A developer declares a domain by hand (in `WarpLinkOptions.linkDomains` or in
 * the manifest), so they paste whatever they have: a dashboard URL, a trailing
 * slash, a stray capital. Anything but the exact host would silently fail to
 * match `Uri.getHost()` and the link would be handed back to the app, which is
 * the very failure this option exists to remove. Normalizing here means a
 * paste-shaped value still works.
 *
 * Deliberately NOT stripping `www.`: it is a real and different host, and an
 * org can serve links from one and not the other.
 *
 * Kept as pure string handling rather than `android.net.Uri` on purpose. The
 * iOS SDK asserts the same table of inputs and outputs, and two parsers only
 * stay in step if neither delegates to a platform URL type whose edge cases
 * differ.
 */
internal fun normalizeLinkDomain(raw: String): String? {
    // lowercase() with no argument is Locale.ROOT, not the device locale. A
    // Turkish locale would otherwise map "LINKS" to "lınks" and never match.
    val trimmed = raw.trim { it in TRIM_SET }.lowercase()
    if (trimmed.isEmpty()) return null

    val authority = stripScheme(trimmed)
        .takeWhile { it != '/' && it != '?' && it != '#' }
    val host = stripPort(stripUserInfo(authority))
    return host.ifEmpty { null }
}

/**
 * Normalize a whole declared list, dropping the entries that normalize away.
 * Returns a set because the sources overlap: the same domain may be listed in
 * options and in the manifest, and the effective domain set is a union anyway.
 */
internal fun normalizeLinkDomains(raw: Collection<String>): Set<String> =
    raw.mapNotNullTo(LinkedHashSet()) { normalizeLinkDomain(it) }

/** Drop `https://`, and the protocol-relative `//` form a copied href can carry. */
private fun stripScheme(value: String): String {
    val separator = value.indexOf("://")
    if (separator >= 0) return value.substring(separator + "://".length)
    return value.removePrefix("//")
}

private fun stripUserInfo(authority: String): String = authority.substringAfterLast('@')

/**
 * Drop a `:443` style port. `Uri.getHost()` never returns one, so a declared
 * host that kept its port could never match.
 *
 * Only when what follows the last colon is all ASCII digits, so an IPv6 literal
 * and a value that is not a URL at all are left intact rather than truncated.
 * ASCII specifically, because Kotlin's `isDigit` and Swift's `isNumber` disagree
 * about other scripts and this has to fold identically on both platforms.
 */
private fun stripPort(host: String): String {
    val colon = host.lastIndexOf(':')
    if (colon < 0) return host
    val port = host.substring(colon + 1)
    return if (port.isNotEmpty() && port.all { it in '0'..'9' }) host.substring(0, colon) else host
}
