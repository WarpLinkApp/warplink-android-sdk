package app.warplink.internal.referral

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The tap id kept with the recovery token the Play referrer carried.
 *
 * The server reads a repeat of a token as the original only when the tap id
 * sent now equals the one stored on the install that holds the token. A token
 * redeem carries no device id and no signals, so a fresh id per launch would
 * make the person's own retry after a lost response read as a replay. One id
 * per token, repeated on every redeem of that token, keeps the retry original.
 *
 * Holds at most one record. It lives under `noBackupFilesDir`, so it belongs
 * to the install that wrote it. Every read-change-write runs under one lock,
 * so [clearIfHolding] is atomic against a newer [tapIdFor].
 */
internal class ReferrerTapStore(directory: File) {

    private val file = InstallJsonFile(directory, FILE_NAME)
    private val lock = Any()

    /**
     * The id for [token]: the one already kept for it, or a new one written
     * before the caller sends. A different token replaces the older record.
     */
    fun tapIdFor(token: String): String = synchronized(lock) {
        heldTapId(token) ?: UUID.randomUUID().toString().also {
            file.write(JSONObject().put(KEY_TOKEN, token).put(KEY_TAP, it))
        }
    }

    /** The id already kept for [token], or null. Writes nothing. */
    fun heldTapId(token: String): String? = synchronized(lock) {
        val held = file.read() ?: return@synchronized null
        if (held.optString(KEY_TOKEN, "") != token) return@synchronized null
        held.optString(KEY_TAP, "").ifEmpty { null }
    }

    /** Drops the record once the server refuses [token]. A newer token's survives. */
    fun clearIfHolding(token: String) = synchronized(lock) {
        if (file.read()?.optString(KEY_TOKEN, "") == token) file.delete()
    }

    fun clear() = synchronized(lock) { file.delete() }

    private companion object {
        const val FILE_NAME = "warplink_referral_referrer_tap.json"
        const val KEY_TOKEN = "token"
        const val KEY_TAP = "tap_id"
    }
}
