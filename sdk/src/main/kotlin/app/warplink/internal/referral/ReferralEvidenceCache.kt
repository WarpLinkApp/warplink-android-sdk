package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.optStringOrNull
import app.warplink.referral.EvidenceClass
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReplayState
import org.json.JSONObject

/**
 * The evidence fields in the cached copy of a match.
 *
 * Kept beside [ReferralEvidenceFields] so the cache and the wire stay one
 * concern each, and the serializer of the older fields takes only two calls.
 * A payload cached by an older SDK has none of these keys and reads back as
 * the safe defaults, so an upgrade never drops a cached match.
 */
internal fun JSONObject.putEvidenceOf(link: WarpLinkDeepLink): JSONObject {
    put("attributionId", link.attributionId ?: JSONObject.NULL)
    put("matchMethod", link.matchMethod?.wireValue ?: JSONObject.NULL)
    put("evidenceClass", link.evidenceClass?.wireValue ?: JSONObject.NULL)
    put("replayState", link.replayState?.wireValue ?: JSONObject.NULL)
    put("capturedParams", JSONObject(link.capturedParams))
    put("tappedAt", link.tappedAt ?: JSONObject.NULL)
    return this
}

internal fun JSONObject.cachedEvidence(): ReferralEvidenceFields = ReferralEvidenceFields(
    attributionId = optStringOrNull("attributionId"),
    matchMethod = MatchMethod.fromWireValue(optStringOrNull("matchMethod")),
    evidenceClass = EvidenceClass.fromWireValue(optStringOrNull("evidenceClass")),
    replayState = ReplayState.fromWireValue(optStringOrNull("replayState")),
    capturedParams = optJSONObject("capturedParams")
        ?.let { ReferralEvidenceFields.capturedParamsOf(it) }
        ?: emptyMap(),
    tappedAt = optStringOrNull("tappedAt"),
)
