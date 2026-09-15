package app.warplink.internal

import android.content.Context
import android.content.pm.PackageManager

/**
 * Manifest meta-data key for a comma separated list of link domains:
 *
 * ```xml
 * <meta-data android:name="app.warplink.DOMAINS"
 *            android:value="links.a.com,links.b.com" />
 * ```
 *
 * The no-code path, for hosts that never touch the `configure()` call site
 * (a library wrapper, a no-code build pipeline). Optional, like the option.
 */
internal const val MANIFEST_DOMAINS_KEY = "app.warplink.DOMAINS"

/**
 * Hand [UriParser] everything the host declared locally: the `linkDomains`
 * option plus the manifest meta-data.
 *
 * Called from `configure()` and synchronous by design. The launch intent for a
 * custom-domain link can arrive before `/sdk/validate` answers, or on a launch
 * where it never answers at all (offline, first run), and the SDK has to decide
 * right then whether the URI is its own. Anything that waits for the network
 * cannot answer that question in time.
 *
 * Applied unconditionally, including when both sources are empty, so that
 * reconfiguring with the option removed drops the previous declaration instead
 * of leaving it live for the rest of the process.
 */
internal fun applyDeclaredLinkDomains(
    context: Context,
    optionDomains: List<String>,
    logger: Logger?
) {
    val declared = normalizeLinkDomains(optionDomains + readManifestLinkDomains(context, logger))
    UriParser.setDeclaredDomains(declared)
    if (declared.isNotEmpty()) {
        logger?.log("Link domains declared locally: $declared")
    }
}

/**
 * Read the raw, comma separated manifest entries. Entries are returned as
 * written; [normalizeLinkDomain] is what makes them comparable to a URI host.
 */
internal fun readManifestLinkDomains(context: Context, logger: Logger?): List<String> {
    val raw = readManifestMetaData(context, logger) ?: return emptyList()
    return raw.split(',')
}

private fun readManifestMetaData(context: Context, logger: Logger?): String? =
    try {
        // The ApplicationInfoFlags overload is API 33+, and minSdk here is 26.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        info.metaData?.getString(MANIFEST_DOMAINS_KEY)
    } catch (e: PackageManager.NameNotFoundException) {
        // An app cannot normally fail to find its own package, so this means a
        // context that is not what it claims to be. Log rather than throw: a
        // missing declaration must not take down Application.onCreate().
        logger?.log("Could not read $MANIFEST_DOMAINS_KEY from the manifest: ${e.message}")
        null
    }
