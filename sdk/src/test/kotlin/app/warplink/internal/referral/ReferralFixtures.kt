package app.warplink.internal.referral

import java.util.Base64

/** Shared values for the referral handoff tests. */
internal object ReferralFixtures {

    const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    const val LINK_ID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    const val INSTALL_ID = "0f1e2d3c-4b5a-4968-8776-655443322110"
    const val INSTALLER_ID = "user-42"

    /**
     * A token with the real shape: 37 bytes, the expiry as a big-endian UInt32
     * at bytes 16..19, written as 50 base64url characters without padding.
     */
    fun token(expiresAtSeconds: Long, filler: Int = 7): String {
        val bytes = ByteArray(37) { filler.toByte() }
        for (i in 0 until 4) {
            bytes[16 + i] = (expiresAtSeconds shr (8 * (3 - i))).toByte()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun now() = System.currentTimeMillis() / 1000

    fun validToken(filler: Int = 7) = token(now() + 3600, filler)

    fun expiredToken() = token(now() - 60)

    const val INVALID_BODY = """{"error":{"code":"RECOVERY_TOKEN_INVALID","message":"server wording"}}"""
    const val EXPIRED_BODY = """{"error":{"code":"RECOVERY_TOKEN_EXPIRED","message":"This recovery token has expired"}}"""
    const val PENDING_BODY = """{"error":{"code":"RECOVERY_TOKEN_PENDING","message":"server wording"}}"""

    /** A match made by the recovery token. */
    const val TOKEN_MATCH_JSON =
        """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"$LINK_ID","destination_url":"https://example.com/promo","deep_link_url":"myapp://promo/42","install_id":"$INSTALL_ID","attribution_id":"attr-1","match_method":"install_token","evidence_class":"exact","replay_state":"original","captured_params":{"utm_campaign":"spring"},"tapped_at":"2026-10-01T10:00:00Z"}"""

    /** A match made by the link id alone, as the server answers an old SDK's call. */
    const val LEGACY_MATCH_JSON =
        """{"matched":true,"match_type":"deterministic","match_confidence":1.0,"match_guaranteed":true,"link_id":"$LINK_ID","destination_url":"https://example.com/promo","deep_link_url":"myapp://promo/42","install_id":"$INSTALL_ID","attribution_id":"attr-2","match_method":"link_open","evidence_class":"legacy_link","replay_state":"original"}"""
}
