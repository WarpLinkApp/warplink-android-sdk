package app.warplink.internal

/**
 * The Play Install Referrer response codes, mirrored from
 * `InstallReferrerClient.InstallReferrerResponse` so a caller (and a test) can
 * name one without depending on the Play library, and turned into text a
 * developer can act on.
 *
 * Every non-OK code produces the same `null` an organic install produces, so
 * the description is the only thing that separates "this user installed
 * organically" from "the referrer API never worked on this device".
 */
internal object InstallReferrerResponseCodes {

    const val OK = 0
    const val SERVICE_DISCONNECTED = -1
    const val SERVICE_UNAVAILABLE = 1
    const val FEATURE_NOT_SUPPORTED = 2
    const val DEVELOPER_ERROR = 3
    const val PERMISSION_ERROR = 4

    /**
     * All the non-OK codes, in the order the Play documentation lists them.
     * Kept complete against the pinned `installreferrer` artifact: the library
     * reports a denied `bindService` only as PERMISSION_ERROR, never as a
     * thrown SecurityException, so an omission here surfaces as
     * "unrecognised" rather than as a name.
     */
    val NON_OK = listOf(
        SERVICE_DISCONNECTED,
        SERVICE_UNAVAILABLE,
        FEATURE_NOT_SUPPORTED,
        DEVELOPER_ERROR,
        PERMISSION_ERROR
    )

    /**
     * The raw code travels with the description because that is what the Play
     * documentation is indexed by, and what a bug report should quote.
     */
    fun describe(responseCode: Int): String = when (responseCode) {
        SERVICE_DISCONNECTED ->
            "Play Store connection lost (code $responseCode)"
        SERVICE_UNAVAILABLE ->
            "Play Store service unavailable (code $responseCode)"
        FEATURE_NOT_SUPPORTED ->
            "Play Store does not support the referrer API on this device " +
                "(code $responseCode)"
        DEVELOPER_ERROR ->
            "Play Store rejected the request as a developer error " +
                "(code $responseCode)"
        PERMISSION_ERROR ->
            "Play Store denied the bind to the referrer service " +
                "(code $responseCode)"
        else ->
            "Play Store returned an unrecognised response (code $responseCode)"
    }
}
