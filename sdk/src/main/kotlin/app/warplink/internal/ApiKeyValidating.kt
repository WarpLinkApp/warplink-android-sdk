package app.warplink.internal

/**
 * The one call [performServerValidation] needs.
 *
 * Narrow on purpose: validation is the only part of the SDK that has to be
 * driven from a test without a socket, and depending on the whole ApiClient
 * would mean faking a class that also does resolve and attribution.
 */
internal fun interface ApiKeyValidating {
    fun validateApiKey(callback: (Result<ValidateResponse>) -> Unit)
}
