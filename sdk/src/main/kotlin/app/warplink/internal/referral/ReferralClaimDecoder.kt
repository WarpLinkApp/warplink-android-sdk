package app.warplink.internal.referral

import app.warplink.ReferralClaimResult
import app.warplink.internal.optDoubleOrNull
import app.warplink.internal.optStringOrNull
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReferralClaimStatus
import app.warplink.referral.ReplayState
import org.json.JSONException
import org.json.JSONObject

internal object ReferralClaimDecoder {

    /**
     * Reads the claim route's 200 body. An unreadable body is `none`, since a
     * claim must never fail the sign-up that triggered it.
     */
    fun decode(body: String?): ReferralClaimResult {
        if (body == null) return ReferralClaimResult(ReferralClaimStatus.NONE)
        return try {
            val json = JSONObject(body)
            val status = ReferralClaimStatus.fromWireValue(json.optStringOrNull("status"))
                ?: return ReferralClaimResult(ReferralClaimStatus.NONE)
            result(status, json)
        } catch (_: JSONException) {
            ReferralClaimResult(ReferralClaimStatus.NONE)
        }
    }

    private fun result(status: ReferralClaimStatus, json: JSONObject): ReferralClaimResult {
        val link = json.optJSONObject("link")
        val match = json.optJSONObject("match")
        return ReferralClaimResult(
            status = status,
            referralId = json.optStringOrNull("referral_id"),
            linkId = link?.optStringOrNull("id"),
            linkUrl = link?.optStringOrNull("url"),
            matchMethod = MatchMethod.fromWireValue(match?.optStringOrNull("method")),
            isExact = match?.optBoolean("exact", false) ?: false,
            matchConfidence = match?.optDoubleOrNull("confidence"),
            flags = flagsOf(json),
            duplicateOf = json.optStringOrNull("duplicate_of"),
            updated = json.optBoolean("updated", false),
            replayState = ReplayState.fromWireValue(json.optStringOrNull("replay_state")),
        )
    }

    private fun flagsOf(json: JSONObject): List<String> {
        val array = json.optJSONArray("flags") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
    }
}
