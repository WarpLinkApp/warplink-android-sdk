package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.optStringOrNull
import app.warplink.referral.EvidenceClass
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReplayState
import org.json.JSONObject

/**
 * The evidence fields the match answer adds in 1.2.0.
 *
 * Every field is optional on the wire: an older server sends none of them, and
 * a value this SDK does not know reads as null. Neither drops the match, and
 * null is the safe reading: an unknown match is never treated as exact.
 */
internal data class ReferralEvidenceFields(
    val attributionId: String? = null,
    val matchMethod: MatchMethod? = null,
    val evidenceClass: EvidenceClass? = null,
    val replayState: ReplayState? = null,
    val capturedParams: Map<String, Any> = emptyMap(),
    val tappedAt: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): ReferralEvidenceFields = ReferralEvidenceFields(
            attributionId = json.optStringOrNull("attribution_id"),
            matchMethod = MatchMethod.fromWireValue(json.optStringOrNull("match_method")),
            evidenceClass = EvidenceClass.fromWireValue(json.optStringOrNull("evidence_class")),
            replayState = ReplayState.fromWireValue(json.optStringOrNull("replay_state")),
            capturedParams = capturedParamsOf(json.optJSONObject("captured_params")),
            tappedAt = json.optStringOrNull("tapped_at"),
        )

        fun capturedParamsOf(json: JSONObject?): Map<String, Any> {
            if (json == null) return emptyMap()
            val params = mutableMapOf<String, Any>()
            for (key in json.keys()) {
                val value = json.opt(key)
                if (value != null && value != JSONObject.NULL) params[key] = value
            }
            return params
        }
    }
}

/** This link with the evidence the match answer carried. */
internal fun WarpLinkDeepLink.withEvidence(evidence: ReferralEvidenceFields): WarpLinkDeepLink =
    copy(
        attributionId = evidence.attributionId,
        matchMethod = evidence.matchMethod,
        evidenceClass = evidence.evidenceClass,
        replayState = evidence.replayState,
        capturedParams = evidence.capturedParams,
        tappedAt = evidence.tappedAt,
    )
