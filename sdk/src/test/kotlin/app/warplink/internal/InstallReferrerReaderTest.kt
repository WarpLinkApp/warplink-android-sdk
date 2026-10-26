package app.warplink.internal

import app.warplink.internal.referral.parseWarpLinkReferrer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallReferrerReaderTest {

    private val validUuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    private fun parseLinkId(referrer: String): String? = parseWarpLinkReferrer(referrer)?.linkId

    @Test
    fun `valid WarpLink referrer returns UUID`() {
        val referrer = "utm_source=warplink&utm_content=$validUuid"
        val result = parseLinkId(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `missing utm_source returns null`() {
        val referrer = "utm_content=$validUuid"
        assertNull(parseLinkId(referrer))
    }

    @Test
    fun `wrong utm_source returns null`() {
        val referrer = "utm_source=other&utm_content=$validUuid"
        assertNull(parseLinkId(referrer))
    }

    @Test
    fun `missing utm_content returns null`() {
        val referrer = "utm_source=warplink"
        assertNull(parseLinkId(referrer))
    }

    @Test
    fun `invalid UUID in utm_content returns null`() {
        val referrer = "utm_source=warplink&utm_content=not-a-uuid"
        assertNull(parseLinkId(referrer))
    }

    @Test
    fun `multiple utm_content returns first one`() {
        val second = "f1e2d3c4-b5a6-4789-8012-3456789abcde"
        val referrer = "utm_source=warplink" +
            "&utm_content=$validUuid&utm_content=$second"
        val result = parseLinkId(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `URL-encoded referrer string is decoded and parsed`() {
        val referrer = "utm_source%3Dwarplink%26utm_content%3D$validUuid"
        val result = parseLinkId(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseLinkId(""))
    }

    @Test
    fun `malformed string returns null`() {
        assertNull(
            parseLinkId("garbage===data")
        )
    }

    @Test
    fun `blank string returns null`() {
        assertNull(parseLinkId("   "))
    }

    @Test
    fun `UUID with uppercase letters is accepted`() {
        val upper = "A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D"
        val referrer = "utm_source=warplink&utm_content=$upper"
        assertEquals(upper, parseLinkId(referrer))
    }

    @Test
    fun `a merged customer referrer still yields the WarpLink link id`() {
        // The Worker prepends our pairs to a customer-supplied referrer rather
        // than refusing it. That is only safe while the first occurrence of a
        // duplicated key wins here. Flip the parser to last-wins and this fails.
        val otherUuid = "0f5b9d1e-6a2c-4d3e-9f8a-1b2c3d4e5f60"
        val referrer = "utm_source=warplink&utm_content=$validUuid" +
            "&utm_source=facebook&utm_campaign=spring_sale&utm_content=$otherUuid"
        assertEquals(validUuid, parseLinkId(referrer))
    }
}
