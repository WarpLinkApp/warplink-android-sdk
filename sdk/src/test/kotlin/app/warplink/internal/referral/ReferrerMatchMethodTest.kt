package app.warplink.internal.referral

import app.warplink.referral.MatchMethod
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The server names the install referrer match `referrer`. Older rows say `android_referrer`. */
@RunWith(RobolectricTestRunner::class)
class ReferrerMatchMethodTest {

    @Test
    fun `WL-S46 referrer and android_referrer read as the same method`() {
        assertEquals(MatchMethod.ANDROID_REFERRER, MatchMethod.fromWireValue("referrer"))
        assertEquals(MatchMethod.ANDROID_REFERRER, MatchMethod.fromWireValue("android_referrer"))
    }

    @Test
    fun `WL-S46 an unknown method still reads as null`() {
        assertNull(MatchMethod.fromWireValue("referrers"))
        assertNull(MatchMethod.fromWireValue(null))
    }

    @Test
    fun `WL-S46 a match answered with referrer keeps its method`() {
        val json = JSONObject().put("match_method", "referrer").put("evidence_class", "exact")

        val fields = ReferralEvidenceFields.fromJson(json)

        assertEquals(MatchMethod.ANDROID_REFERRER, fields.matchMethod)
    }
}
