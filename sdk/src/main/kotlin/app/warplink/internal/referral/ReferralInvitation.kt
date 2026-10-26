package app.warplink.internal.referral

import java.util.Base64

/**
 * One recovery token, as a person pastes it or as the Play referrer carries it.
 *
 * An invitation is exactly one token and nothing else: 37 bytes, written as 50
 * URL-safe base64 characters without padding. Anything that is not that shape
 * is not an invitation and is never sent anywhere, so text that belongs to
 * something else stays on the device.
 *
 * Only the shape and the expiry are read here. The signature and the app the
 * token belongs to are checked by the server, which is the only authority.
 *
 * @property token The token, trimmed.
 * @property expiresAtSeconds When the server stops accepting the token, in
 * seconds since the epoch.
 */
internal data class ReferralInvitation(
    val token: String,
    val expiresAtSeconds: Long,
) {

    /** Whether the token has run out at [nowSeconds]. The expiry second itself counts. */
    fun isExpired(nowSeconds: Long): Boolean = expiresAtSeconds <= nowSeconds

    companion object {
        private const val ENCODED_LENGTH = 50
        private const val DECODED_LENGTH = 37
        private const val EXPIRY_START = 16
        private const val EXPIRY_BYTES = 4
        private val ALPHABET = Regex("^[A-Za-z0-9_-]+$")

        /** The invitation in [text], or null when [text] is not one. */
        fun parse(text: String): ReferralInvitation? {
            val candidate = text.trim()
            if (candidate.length != ENCODED_LENGTH || !ALPHABET.matches(candidate)) return null
            val bytes = decode(candidate) ?: return null
            if (bytes.size != DECODED_LENGTH) return null
            var seconds = 0L
            for (i in EXPIRY_START until EXPIRY_START + EXPIRY_BYTES) {
                seconds = (seconds shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return ReferralInvitation(candidate, seconds)
        }

        private fun decode(encoded: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
