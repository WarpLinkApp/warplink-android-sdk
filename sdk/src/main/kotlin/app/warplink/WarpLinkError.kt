package app.warplink

sealed class WarpLinkError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    data object NotConfigured : WarpLinkError(
        "WarpLink SDK is not configured. Call WarpLink.configure() first."
    )

    data object InvalidApiKeyFormat : WarpLinkError(
        "Invalid API key format. Expected format: wl_(live|test)_<32 alphanumeric chars>"
    )

    data object InvalidApiKey : WarpLinkError(
        "API key is invalid or has been revoked."
    )

    class NetworkError(cause: Throwable) : WarpLinkError(
        "Network request failed: ${cause.localizedMessage}",
        cause
    )

    class ServerError(
        val statusCode: Int,
        override val message: String
    ) : WarpLinkError("Server error ($statusCode): $message")

    data object InvalidUrl : WarpLinkError(
        "The provided URL is not a valid WarpLink deep link."
    )

    data object LinkNotFound : WarpLinkError(
        "The requested link was not found."
    )

    /**
     * The link is password protected, so resolving it returns no destination
     * and no platform URLs. Send the user to the short URL in a browser, where
     * the password form lives.
     */
    data object PasswordRequired : WarpLinkError(
        "This link is password protected. Open it in a browser to enter the password."
    )

    class DecodingError(cause: Throwable) : WarpLinkError(
        "Failed to decode server response: ${cause.localizedMessage}",
        cause
    )
}
