package app.warplink.internal

import android.net.Uri

internal object UriParser {

    private val KNOWN_DOMAINS = setOf("aplnk.to")
    private const val DEFAULT_DOMAIN = "aplnk.to"

    fun isWarpLinkUri(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return KNOWN_DOMAINS.contains(host)
    }

    fun extractSlug(uri: Uri): String? {
        return uri.pathSegments.firstOrNull()?.takeIf {
            it.isNotEmpty()
        }
    }

    fun extractDomain(uri: Uri): String {
        return uri.host ?: DEFAULT_DOMAIN
    }
}
