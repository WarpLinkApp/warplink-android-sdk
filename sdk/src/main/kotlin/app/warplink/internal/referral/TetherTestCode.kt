package app.warplink.internal.referral

/**
 * A Tether test code, as the Play referrer carries it in `wl_rt`.
 *
 * Only the shape is read: the `wl1t_` prefix and a body after it. The server
 * checks the signature and the expiry, and it records nothing for a test code.
 * A live `wl1_` code never travels this way on Android, so it is not parsed here.
 */
internal object TetherTestCode {
    private const val PREFIX = "wl1t_"
    private const val MAX_LENGTH = 200

    /** The trimmed code in [text], or null when [text] is not a test code. */
    fun parse(text: String): String? {
        val candidate = text.trim()
        val shaped = candidate.length in (PREFIX.length + 1)..MAX_LENGTH &&
            candidate.startsWith(PREFIX)
        return candidate.takeIf { shaped }
    }
}
