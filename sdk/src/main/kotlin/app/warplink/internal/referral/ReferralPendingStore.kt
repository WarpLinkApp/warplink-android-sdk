package app.warplink.internal.referral

import app.warplink.internal.KeyEnvironment
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The one invitation that has not been delivered yet.
 *
 * @property attempts Redeem runs started for this token, counting the one in
 * progress.
 * @property tapId One id for this token, sent as the tap id on every redeem of
 * it. The server tells the person's own retry from a replay by this id.
 */
internal data class PendingRedemption(
    val token: String,
    val expiresAtSeconds: Long,
    val attempts: Int = 0,
    val tapId: String = UUID.randomUUID().toString(),
) {
    fun isExpired(nowSeconds: Long): Boolean = expiresAtSeconds <= nowSeconds

    val hasAttemptsLeft: Boolean get() = attempts < MAX_ATTEMPTS

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * Keeps the invitation on the device so a network failure or a 503 can be
 * retried on a later launch, and so a retry never delivers the match twice.
 *
 * Holds at most one record: a newer invitation replaces the older one. The
 * record lives under `noBackupFilesDir`, so it is honoured only for the
 * install that kept it. Every read-change-write runs under one lock, so
 * [clearIfHolding] is atomic against a newer [save].
 */
internal class ReferralPendingStore(
    directory: File,
    environment: KeyEnvironment = KeyEnvironment.PRODUCTION,
) {

    private val file = InstallJsonFile(directory, environment.scoped(FILE_BASE, ".json"))
    private val lock = Any()

    fun load(): PendingRedemption? = synchronized(lock) {
        val json = file.read() ?: return@synchronized null
        val token = json.optString(KEY_TOKEN, "")
        if (token.isEmpty() || !json.has(KEY_EXPIRES)) {
            file.delete()
            return@synchronized null
        }
        PendingRedemption(
            token,
            json.getLong(KEY_EXPIRES),
            json.optInt(KEY_ATTEMPTS, 0),
            json.optString(KEY_TAP, "").ifEmpty { UUID.randomUUID().toString() },
        )
    }

    fun save(record: PendingRedemption): Boolean = synchronized(lock) {
        file.write(
            JSONObject()
                .put(KEY_TOKEN, record.token)
                .put(KEY_EXPIRES, record.expiresAtSeconds)
                .put(KEY_ATTEMPTS, record.attempts)
                .put(KEY_TAP, record.tapId)
        )
    }

    /**
     * Clears the record only while it is still [token]'s. A newer invitation
     * may have replaced it during the request, and that one must survive.
     */
    fun clearIfHolding(token: String) = synchronized(lock) {
        if (load()?.token == token) file.delete()
    }

    /** True when the record is gone. */
    fun clear(): Boolean = synchronized(lock) { file.delete() }

    private companion object {
        const val FILE_BASE = "warplink_referral_pending"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_ATTEMPTS = "attempts"
        const val KEY_TAP = "tap_id"
    }
}
