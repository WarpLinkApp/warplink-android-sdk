package app.warplink.internal

import app.warplink.internal.referral.ReferralEvidenceFields

internal data class AttributionResponse(
    val matched: Boolean,
    val matchType: String?,
    val matchConfidence: Double?,
    val matchGuaranteed: Boolean = false,
    val linkId: String?,
    val deepLinkUrl: String?,
    val destinationUrl: String?,
    val customParams: Map<String, Any>?,
    val installId: String?,
    /** The 1.2.0 evidence fields, absent from an older server's answer. */
    val evidence: ReferralEvidenceFields = ReferralEvidenceFields()
)
