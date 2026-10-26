package app.warplink.internal.referral

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WarpLinkReferrerTest {

    private val validUuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val token = "Zm9vYmFyYmF6cXV4_-Zm9vYmFyYmF6cXV4AAAAAAAAAAA"

    private fun linkId(referrer: String): String? =
        parseWarpLinkReferrer(referrer)?.linkId

    @Test
    fun `valid WarpLink referrer returns the link id`() {
        assertEquals(validUuid, linkId("utm_source=warplink&utm_content=$validUuid"))
    }

    @Test
    fun `missing utm_source returns null`() {
        assertNull(parseWarpLinkReferrer("utm_content=$validUuid"))
    }

    @Test
    fun `wrong utm_source returns null`() {
        assertNull(parseWarpLinkReferrer("utm_source=other&utm_content=$validUuid"))
    }

    @Test
    fun `missing utm_content returns null`() {
        assertNull(parseWarpLinkReferrer("utm_source=warplink"))
    }

    @Test
    fun `invalid UUID in utm_content returns null`() {
        assertNull(parseWarpLinkReferrer("utm_source=warplink&utm_content=not-a-uuid"))
    }

    @Test
    fun `multiple utm_content returns first one`() {
        val second = "f1e2d3c4-b5a6-4789-8012-3456789abcde"
        val referrer = "utm_source=warplink" +
            "&utm_content=$validUuid&utm_content=$second"
        assertEquals(validUuid, linkId(referrer))
    }

    @Test
    fun `URL-encoded referrer string is decoded and parsed`() {
        val referrer = "utm_source%3Dwarplink%26utm_content%3D$validUuid"
        assertEquals(validUuid, linkId(referrer))
    }

    @Test
    fun `empty, blank and malformed strings return null`() {
        assertNull(parseWarpLinkReferrer(""))
        assertNull(parseWarpLinkReferrer("   "))
        assertNull(parseWarpLinkReferrer("garbage===data"))
    }

    @Test
    fun `UUID with uppercase letters is accepted`() {
        val upper = "A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D"
        assertEquals(upper, linkId("utm_source=warplink&utm_content=$upper"))
    }

    @Test
    fun `a merged customer referrer still yields the WarpLink link id`() {
        // The Worker prepends our pairs to a customer-supplied referrer rather
        // than refusing it. That is only safe while the first occurrence of a
        // duplicated key wins here. Flip the parser to last-wins and this fails.
        val otherUuid = "0f5b9d1e-6a2c-4d3e-9f8a-1b2c3d4e5f60"
        val referrer = "utm_source=warplink&utm_content=$validUuid" +
            "&utm_source=facebook&utm_campaign=spring_sale&utm_content=$otherUuid"
        assertEquals(validUuid, linkId(referrer))
    }

    @Test
    fun `the recovery token rides next to the link id`() {
        val parsed = parseWarpLinkReferrer(
            "utm_source=warplink&utm_content=$validUuid&wl_rt=$token"
        )
        assertEquals(WarpLinkReferrer(validUuid, token), parsed)
    }

    @Test
    fun `a recovery token without a link id is still a read`() {
        val parsed = parseWarpLinkReferrer("utm_source=warplink&wl_rt=$token")
        assertEquals(WarpLinkReferrer(null, token), parsed)
    }

    @Test
    fun `a link id without a recovery token has a null token`() {
        val parsed = parseWarpLinkReferrer("utm_source=warplink&utm_content=$validUuid")
        assertEquals(WarpLinkReferrer(validUuid, null), parsed)
    }

    @Test
    fun `a recovery token on a referrer that is not ours is ignored`() {
        assertNull(parseWarpLinkReferrer("utm_source=other&wl_rt=$token"))
        assertNull(parseWarpLinkReferrer("wl_rt=$token"))
    }

    @Test
    fun `the customer's own wl_rt cannot replace ours`() {
        val referrer = "utm_source=warplink&utm_content=$validUuid&wl_rt=$token" +
            "&wl_rt=intruder"
        assertEquals(token, parseWarpLinkReferrer(referrer)?.recoveryToken)
    }

    @Test
    fun `merged customer parameters leave the token readable`() {
        val referrer = "utm_source=warplink&utm_content=$validUuid&wl_rt=$token" +
            "&utm_source=facebook&utm_campaign=a%26b"
        assertEquals(WarpLinkReferrer(validUuid, token), parseWarpLinkReferrer(referrer))
    }

    @Test
    fun `a token with characters outside the token alphabet is dropped`() {
        val parsed = parseWarpLinkReferrer(
            "utm_source=warplink&utm_content=$validUuid&wl_rt=abc%26def%20"
        )
        assertEquals(WarpLinkReferrer(validUuid, null), parsed)
        assertNull(parseWarpLinkReferrer("utm_source=warplink&wl_rt=abc%26def"))
    }

    @Test
    fun `a token over 256 characters is dropped`() {
        val longToken = "a".repeat(257)
        assertNull(parseWarpLinkReferrer("utm_source=warplink&wl_rt=$longToken"))
        val atLimit = "a".repeat(256)
        assertEquals(
            atLimit,
            parseWarpLinkReferrer("utm_source=warplink&wl_rt=$atLimit")?.recoveryToken
        )
    }

    @Test
    fun `an empty token is dropped`() {
        assertNull(parseWarpLinkReferrer("utm_source=warplink&wl_rt="))
    }
}
