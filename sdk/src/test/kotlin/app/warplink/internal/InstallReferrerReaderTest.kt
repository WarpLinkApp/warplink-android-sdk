package app.warplink.internal

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class InstallReferrerReaderTest {

    private val validUuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    @Test
    fun `valid WarpLink referrer returns UUID`() {
        val referrer = "utm_source=warplink&utm_content=$validUuid"
        val result = InstallReferrerReader.parseWarpLinkReferrer(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `missing utm_source returns null`() {
        val referrer = "utm_content=$validUuid"
        assertNull(InstallReferrerReader.parseWarpLinkReferrer(referrer))
    }

    @Test
    fun `wrong utm_source returns null`() {
        val referrer = "utm_source=other&utm_content=$validUuid"
        assertNull(InstallReferrerReader.parseWarpLinkReferrer(referrer))
    }

    @Test
    fun `missing utm_content returns null`() {
        val referrer = "utm_source=warplink"
        assertNull(InstallReferrerReader.parseWarpLinkReferrer(referrer))
    }

    @Test
    fun `invalid UUID in utm_content returns null`() {
        val referrer = "utm_source=warplink&utm_content=not-a-uuid"
        assertNull(InstallReferrerReader.parseWarpLinkReferrer(referrer))
    }

    @Test
    fun `multiple utm_content returns first one`() {
        val second = "f1e2d3c4-b5a6-4789-8012-3456789abcde"
        val referrer = "utm_source=warplink" +
            "&utm_content=$validUuid&utm_content=$second"
        val result = InstallReferrerReader.parseWarpLinkReferrer(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `URL-encoded referrer string is decoded and parsed`() {
        val referrer = "utm_source%3Dwarplink%26utm_content%3D$validUuid"
        val result = InstallReferrerReader.parseWarpLinkReferrer(referrer)
        assertEquals(validUuid, result)
    }

    @Test
    fun `empty string returns null`() {
        assertNull(InstallReferrerReader.parseWarpLinkReferrer(""))
    }

    @Test
    fun `malformed string returns null`() {
        assertNull(
            InstallReferrerReader.parseWarpLinkReferrer("garbage===data")
        )
    }

    @Test
    fun `blank string returns null`() {
        assertNull(InstallReferrerReader.parseWarpLinkReferrer("   "))
    }

    @Test
    fun `UUID with uppercase letters is accepted`() {
        val upper = "A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D"
        val referrer = "utm_source=warplink&utm_content=$upper"
        assertEquals(upper, InstallReferrerReader.parseWarpLinkReferrer(referrer))
    }
}
