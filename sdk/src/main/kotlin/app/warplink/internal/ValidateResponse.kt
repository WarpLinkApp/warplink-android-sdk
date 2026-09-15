package app.warplink.internal

/**
 * Result of `GET /sdk/validate`.
 *
 * @property valid Whether the API key is active.
 * @property domains Link domains usable by this org (always includes `aplnk.to`
 *   server-side). The SDK treats a URL whose host is in this set as a WarpLink
 *   link, so custom domains resolve without a hardcoded allowlist. Empty when
 *   the server response predates the domains field.
 */
internal data class ValidateResponse(
    val valid: Boolean,
    val domains: List<String>
)
