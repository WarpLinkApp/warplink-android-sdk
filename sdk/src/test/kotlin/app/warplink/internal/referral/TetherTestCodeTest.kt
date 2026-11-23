package app.warplink.internal.referral

import app.warplink.internal.TetherResponse
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The test code shape Android recognises, and the `tether` object it reads back. */
@RunWith(RobolectricTestRunner::class)
class TetherTestCodeTest {

    @Test
    fun `WL-S60 a wl1t code with a body is a test code and is trimmed`() {
        assertEquals("wl1t_abc", TetherTestCode.parse("  wl1t_abc\n"))
    }

    @Test
    fun `WL-S60 a live code, an empty body and other text are not test codes`() {
        assertNull(TetherTestCode.parse("wl1_abc"))
        assertNull(TetherTestCode.parse("wl1t_"))
        assertNull(TetherTestCode.parse("hello"))
        assertNull(TetherTestCode.parse(""))
    }

    @Test
    fun `WL-S60 a code over 200 characters is not a test code`() {
        val longest = "wl1t_" + "a".repeat(195)
        assertEquals(longest, TetherTestCode.parse(longest))
        assertNull(TetherTestCode.parse(longest + "a"))
    }

    @Test
    fun `WL-S60 the tether object is read with its defaults`() {
        val full = JSONObject(
            """{"status":"test","accepted":false,"test":true,"test_receipt":"r1"}""",
        )
        assertEquals(TetherResponse("test", false, true, "r1"), TetherResponse.fromJson(full))

        val bare = TetherResponse.fromJson(JSONObject("""{"status":"accepted","accepted":true}"""))
        assertEquals(TetherResponse("accepted", true, false, null), bare)
    }

    @Test
    fun `WL-S60 a pending answer reads with its wait and is neither accepted nor a test`() {
        val pending = TetherResponse.fromJson(
            JSONObject("""{"status":"pending","accepted":false,"test":false,"retry_after_seconds":20}"""),
        )
        assertEquals(TetherResponse("pending", false, false, null, 20), pending)

        val noHint = TetherResponse.fromJson(JSONObject("""{"status":"pending","accepted":false}"""))
        assertEquals(TetherResponse("pending", false, false, null, null), noHint)
    }

    @Test
    fun `WL-S60 an absent tether object or status reads as null`() {
        assertNull(TetherResponse.fromJson(null))
        assertNull(TetherResponse.fromJson(JSONObject("""{"accepted":true}""")))
    }
}
