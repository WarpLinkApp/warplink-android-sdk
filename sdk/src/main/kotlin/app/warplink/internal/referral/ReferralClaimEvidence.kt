package app.warplink.internal.referral

import org.json.JSONObject
import java.util.UUID

/**
 * What a claim sends to name the referral: the install the match created and
 * the recovery token behind it. Both are optional on the wire.
 */
internal data class ReferralClaimEvidence(
    val installId: String?,
    val recoveryToken: String?,
) {
    fun body(installerId: String, packageName: String?): JSONObject {
        val body = JSONObject()
            .put("installer_id", installerId)
            .put("platform", "android")
        if (!packageName.isNullOrEmpty()) body.put("app_package_name", packageName)
        installId?.let { body.put("install_id", it) }
        recoveryToken?.let { body.put("recovery_token", it) }
        return body
    }

    companion object {
        /**
         * Reads what the SDK holds. A token that has run out is left out, since
         * the server would refuse it, and so is an install id that is not a
         * UUID, for the same reason.
         */
        fun current(store: ReferralEvidenceStore, nowSeconds: Long): ReferralClaimEvidence {
            val held = store.evidence()
            return ReferralClaimEvidence(
                installId = held.installId?.takeIf(::isUuid),
                recoveryToken = held.usableToken(nowSeconds),
            )
        }

        private fun isUuid(value: String): Boolean = try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
