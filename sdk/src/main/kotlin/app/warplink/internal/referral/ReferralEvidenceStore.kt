package app.warplink.internal.referral

import app.warplink.internal.KeyEnvironment
import org.json.JSONObject
import java.io.File

/**
 * What the SDK remembers from a match so a later claim can name it.
 *
 * A claim happens after sign-up, often in a later session, when the match
 * response is long gone. The claim route accepts the install the match created
 * and the recovery token behind it, so both are kept here. Neither is shown to
 * the host.
 */
internal data class ReferralEvidence(
    val installId: String? = null,
    val recoveryToken: String? = null,
    val recoveryTokenExpiresAtSeconds: Long? = null,
) {
    /** The token, or null when none is held or it has run out. */
    fun usableToken(nowSeconds: Long): String? {
        val token = recoveryToken ?: return null
        val expiry = recoveryTokenExpiresAtSeconds
        return if (expiry != null && expiry <= nowSeconds) null else token
    }
}

/**
 * Persists [ReferralEvidence] under `noBackupFilesDir`, so an install id and a
 * token belong to the install that earned them. Every change is one
 * read-change-write under one lock, so two writers cannot overwrite each other
 * and the check inside [clearToken] still holds when it writes.
 */
internal class ReferralEvidenceStore(
    directory: File,
    environment: KeyEnvironment = KeyEnvironment.PRODUCTION,
) {

    private val file = InstallJsonFile(directory, environment.scoped(FILE_BASE, ".json"))
    private val lock = Any()

    fun evidence(): ReferralEvidence = synchronized(lock) { read() }

    /** Remembers the install a match created. A null leaves the earlier value alone. */
    fun rememberInstall(installId: String?) {
        if (installId == null) return
        update { it.copy(installId = installId) }
    }

    /** Remembers the token a claim should carry. A newer token replaces the older one. */
    fun rememberToken(token: String, expiresAtSeconds: Long?) {
        update { it.copy(recoveryToken = token, recoveryTokenExpiresAtSeconds = expiresAtSeconds) }
    }

    /**
     * Drops the held token once the server has refused it, so no later claim
     * sends it again. Kept only while it is still [token]: a newer one may have
     * replaced it during the request. The install id stays, since the claim can
     * still name the install without a token.
     */
    fun clearToken(ifHolding: String) {
        update {
            if (it.recoveryToken == ifHolding) {
                it.copy(recoveryToken = null, recoveryTokenExpiresAtSeconds = null)
            } else {
                it
            }
        }
    }

    /** True when the record is gone. */
    fun clear(): Boolean = synchronized(lock) { file.delete() }

    private fun update(change: (ReferralEvidence) -> ReferralEvidence) = synchronized(lock) {
        write(change(read()))
    }

    private fun read(): ReferralEvidence {
        val json = file.read() ?: return ReferralEvidence()
        return ReferralEvidence(
            installId = json.optString(KEY_INSTALL, "").ifEmpty { null },
            recoveryToken = json.optString(KEY_TOKEN, "").ifEmpty { null },
            recoveryTokenExpiresAtSeconds =
                if (json.has(KEY_EXPIRES)) json.optLong(KEY_EXPIRES) else null,
        )
    }

    private fun write(evidence: ReferralEvidence) {
        val json = JSONObject()
        evidence.installId?.let { json.put(KEY_INSTALL, it) }
        evidence.recoveryToken?.let { json.put(KEY_TOKEN, it) }
        evidence.recoveryTokenExpiresAtSeconds?.let { json.put(KEY_EXPIRES, it) }
        file.write(json)
    }

    private companion object {
        const val FILE_BASE = "warplink_referral_evidence"
        const val KEY_INSTALL = "install_id"
        const val KEY_TOKEN = "recovery_token"
        const val KEY_EXPIRES = "recovery_token_expires_at"
    }
}
