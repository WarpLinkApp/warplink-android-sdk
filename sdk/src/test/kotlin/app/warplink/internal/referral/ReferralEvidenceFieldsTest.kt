package app.warplink.internal.referral

import app.warplink.WarpLinkDeepLink
import app.warplink.internal.deserializeDeepLink
import app.warplink.internal.serializeDeepLink
import app.warplink.referral.EvidenceClass
import app.warplink.referral.MatchMethod
import app.warplink.referral.ReplayState
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The additive evidence fields of a match, and how an older or newer payload reads. */
@RunWith(RobolectricTestRunner::class)
class ReferralEvidenceFieldsTest {

    @Test
    fun `WL-S43 a matched answer carries every evidence field`() {
        val fields = ReferralEvidenceFields.fromJson(JSONObject(ReferralFixtures.TOKEN_MATCH_JSON))

        assertEquals("attr-1", fields.attributionId)
        assertEquals(MatchMethod.INSTALL_TOKEN, fields.matchMethod)
        assertEquals(EvidenceClass.EXACT, fields.evidenceClass)
        assertEquals(ReplayState.ORIGINAL, fields.replayState)
        assertEquals(mapOf<String, Any>("utm_campaign" to "spring"), fields.capturedParams)
        assertEquals("2026-10-01T10:00:00Z", fields.tappedAt)
    }

    @Test
    fun `WL-S43 an answer from an older server decodes to safe defaults`() {
        val fields = ReferralEvidenceFields.fromJson(JSONObject("""{"matched":true}"""))

        assertEquals(ReferralEvidenceFields(), fields)
        assertNull(fields.matchMethod)
        assertEquals(emptyMap(), fields.capturedParams)
    }

    @Test
    fun `WL-S43 a value this SDK does not know reads as null and never drops the match`() {
        val json = JSONObject(
            """{"match_method":"quantum","evidence_class":"mystery","replay_state":"later","attribution_id":"a1"}"""
        )

        val fields = ReferralEvidenceFields.fromJson(json)

        assertNull(fields.matchMethod)
        assertNull(fields.evidenceClass)
        assertNull(fields.replayState)
        assertEquals("a1", fields.attributionId)
    }

    @Test
    fun `WL-S43 a null captured params field reads as empty`() {
        val fields = ReferralEvidenceFields.fromJson(JSONObject("""{"captured_params":null}"""))

        assertEquals(emptyMap(), fields.capturedParams)
    }

    @Test
    fun `WL-S43 the evidence survives the cache round trip`() {
        val link = WarpLinkDeepLink(
            linkId = ReferralFixtures.LINK_ID,
            destination = "https://example.com/promo",
            attributionId = "attr-1",
            matchMethod = MatchMethod.INSTALL_TOKEN,
            evidenceClass = EvidenceClass.EXACT,
            replayState = ReplayState.REPLAYED,
            capturedParams = mapOf("utm_campaign" to "spring"),
            tappedAt = "2026-10-01T10:00:00Z",
        )

        val restored = deserializeDeepLink(serializeDeepLink(link))

        assertEquals(link, restored)
    }

    @Test
    fun `WL-S43 a payload cached by an older SDK still reads as a match`() {
        val older = JSONObject(
            """{"linkId":"${ReferralFixtures.LINK_ID}","destination":"https://example.com","isDeferred":true,"matchGuaranteed":false,"customParams":{}}"""
        )

        val restored = deserializeDeepLink(older)

        assertEquals(ReferralFixtures.LINK_ID, restored.linkId)
        assertNull(restored.attributionId)
        assertNull(restored.matchMethod)
        assertNull(restored.evidenceClass)
        assertNull(restored.replayState)
        assertEquals(emptyMap(), restored.capturedParams)
    }

    @Test
    fun `WL-S46 a link id match keeps its legacy method and class`() {
        val fields = ReferralEvidenceFields.fromJson(JSONObject(ReferralFixtures.LEGACY_MATCH_JSON))

        assertEquals(MatchMethod.LINK_OPEN, fields.matchMethod)
        assertEquals(EvidenceClass.LEGACY_LINK, fields.evidenceClass)
    }

    @Test
    fun `WL-S46 a token backed match is never reported as a legacy link match`() {
        val fields = ReferralEvidenceFields.fromJson(JSONObject(ReferralFixtures.TOKEN_MATCH_JSON))

        assertEquals(MatchMethod.INSTALL_TOKEN, fields.matchMethod)
        assertEquals(EvidenceClass.EXACT, fields.evidenceClass)
    }

    @Test
    fun `WL-S46 a legacy match survives the cache without being upgraded`() {
        val legacy = WarpLinkDeepLink(
            linkId = ReferralFixtures.LINK_ID,
            destination = "https://example.com",
        ).withEvidence(ReferralEvidenceFields.fromJson(JSONObject(ReferralFixtures.LEGACY_MATCH_JSON)))

        val restored = deserializeDeepLink(serializeDeepLink(legacy))

        assertEquals(MatchMethod.LINK_OPEN, restored.matchMethod)
        assertEquals(EvidenceClass.LEGACY_LINK, restored.evidenceClass)
    }
}
