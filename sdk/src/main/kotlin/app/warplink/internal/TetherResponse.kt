package app.warplink.internal

import org.json.JSONObject

/**
 * The `tether` object of an attribution answer. Absent from an older server's
 * answer, and from any request that carried no Tether code.
 *
 * @property test true when the code was a test code. The answer then holds a
 * [testReceipt] and the server recorded nothing.
 * @property status `accepted`, `not_found`, `rejected`, `test` or `pending`. Any other
 * value is kept as it is, so a status a newer server adds never breaks a read.
 * @property retryAfterSeconds for `pending` only: the wait the server suggests. Android
 * sends no live code, so it never acts on a pending answer, and only reads it.
 */
internal data class TetherResponse(
    val status: String,
    val accepted: Boolean,
    val test: Boolean,
    val testReceipt: String?,
    val retryAfterSeconds: Int? = null,
) {
    companion object {
        /** The response in [json], or null when it is absent or has no status. */
        fun fromJson(json: JSONObject?): TetherResponse? {
            val status = json?.optStringOrNull("status") ?: return null
            return TetherResponse(
                status = status,
                accepted = json.optBoolean("accepted", false),
                test = json.optBoolean("test", false),
                testReceipt = json.optStringOrNull("test_receipt"),
                retryAfterSeconds = if (json.has("retry_after_seconds") && !json.isNull("retry_after_seconds")) json.optInt("retry_after_seconds") else null,
            )
        }
    }
}
