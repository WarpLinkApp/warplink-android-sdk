package app.warplink.internal

import android.net.Uri

internal object UriParser {

    const val DEFAULT_DOMAIN = "aplnk.to"

    private val lock = Any()

    // The sources are held apart and the effective set is DERIVED, rather than
    // one field each source overwrites. They arrive at different times and no
    // source is authoritative: the server answer landing must not erase what
    // the host declared locally, and a local declaration must not hide a domain
    // the org added after this app shipped.
    //
    // Declared locally in configure() (options + manifest). The only source
    // available synchronously, so it is the one that lets a custom-domain link
    // be claimed on a first launch, before /sdk/validate has answered.
    private var declaredDomains: Set<String> = emptySet()

    // From /sdk/validate, and from the cached copy of its last answer.
    private var serverDomains: Set<String> = emptySet()

    // The derived union. Cached rather than recomputed per call because
    // isWarpLinkUri sits on the launch path, while the sources change at most
    // a couple of times in a process. @Volatile so a link arriving on the main
    // thread sees a set published from the validation callback's thread.
    @Volatile
    private var effectiveDomains: Set<String> = setOf(DEFAULT_DOMAIN)

    /** Apply the domains the host declared locally (options + manifest). */
    fun setDeclaredDomains(domains: Collection<String>) = synchronized(lock) {
        declaredDomains = normalizeLinkDomains(domains)
        recomputeEffectiveDomains()
    }

    /** Apply the org's domains from `/sdk/validate`, or from its cache. */
    fun setServerDomains(domains: Collection<String>) = synchronized(lock) {
        serverDomains = normalizeLinkDomains(domains)
        recomputeEffectiveDomains()
    }

    /** Drop every source, back to the default-only set (used by reset()). */
    fun resetKnownDomains() = synchronized(lock) {
        declaredDomains = emptySet()
        serverDomains = emptySet()
        recomputeEffectiveDomains()
    }

    // `aplnk.to` is always in the union so the default short domain resolves
    // even when no other source has produced anything.
    private fun recomputeEffectiveDomains() {
        effectiveDomains = declaredDomains + serverDomains + DEFAULT_DOMAIN
    }

    /**
     * Whether [uri] is a WarpLink App Link the SDK can resolve: a known host AND
     * a path that carries a slug (see [extractSlug]).
     *
     * The slug condition matters because App Links are verified for the whole
     * host, so the OS hands the app every URL on the domain, including marketing
     * pages and multi-segment paths that are not links. Claiming those would
     * swallow URIs the SDK cannot resolve instead of letting the host app's own
     * routing handle them.
     */
    fun isWarpLinkUri(uri: Uri): Boolean {
        // Hosts are case-insensitive and every declared domain is lowercased,
        // so the incoming one is lowered for the comparison rather than trusted
        // to already be lowercase.
        val host = uri.host?.lowercase() ?: return false
        if (!effectiveDomains.contains(host)) return false
        return extractSlug(uri) != null
    }

    /**
     * Extract the WarpLink slug from [uri], or null when the path is not a slug.
     *
     * A slug is always exactly one path segment: every server-side create path
     * rejects `/` in a slug, and the redirect edge only matches single-segment
     * paths. So `https://aplnk.to/abc123` yields `"abc123"`, while
     * `https://aplnk.to/blog/hello` yields null rather than `"blog"`, which
     * would resolve an unrelated link that happens to be named `blog`.
     *
     * The guard is on segment count only, deliberately not on the server's slug
     * character rules. Segment count is structural (a slug can never contain
     * `/`), so it cannot drift the way a copy of those rules would once they
     * loosen server-side, in a binary that is already shipped.
     *
     * `pathSegments` skips empty segments, so a trailing slash is still a single
     * segment. It does decode each segment, so a percent-encoded `%2F` survives
     * inside one: reject that too, since the slug goes into the resolve URL.
     */
    fun extractSlug(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 1) return null
        return segments[0].takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    /**
     * The host to resolve this link against. Lowercased for the same reason the
     * gate lowercases: the domain is sent to the resolve API, which stores
     * domains lowercased, so a mixed-case host would resolve to nothing.
     */
    fun extractDomain(uri: Uri): String {
        return uri.host?.lowercase() ?: DEFAULT_DOMAIN
    }
}
