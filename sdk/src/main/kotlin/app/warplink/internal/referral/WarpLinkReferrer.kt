package app.warplink.internal.referral

private const val WARPLINK_SOURCE = "warplink"

private val UUID_REGEX = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}" +
        "-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE
)

/** The shape of a per-tap recovery token: URL-safe characters, 1 to 256 long. */
private val RECOVERY_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{1,256}$")

/**
 * What a WarpLink redirect left in the Play install referrer.
 *
 * @property linkId The tapped link, when `utm_content` is a UUID.
 * @property recoveryToken The per-tap token from `wl_rt`, opaque to the SDK.
 * It is never logged.
 */
internal data class WarpLinkReferrer(
    val linkId: String?,
    val recoveryToken: String?,
)

/**
 * Reads WarpLink's pairs out of a Play install referrer, or returns null when
 * the referrer is not ours (another `utm_source`) or carries neither a link id
 * nor a recovery token.
 */
internal fun parseWarpLinkReferrer(referrer: String): WarpLinkReferrer? {
    val query = parseReferrerQuery(referrer)
    if (query["utm_source"] != WARPLINK_SOURCE) return null
    val linkId = query["utm_content"]?.takeIf { UUID_REGEX.matches(it) }
    val token = query["wl_rt"]?.takeIf { RECOVERY_TOKEN_REGEX.matches(it) }
    if (linkId == null && token == null) return null
    return WarpLinkReferrer(linkId, token)
}
