package app.warplink.internal.referral

import app.warplink.internal.referral.ReferralFixtures.token
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferralInvitationTest {

    private val expiry = 1_900_000_000L

    @Test
    fun `WL-S40 a 50 character base64url token of 37 bytes is an invitation`() {
        val text = token(expiry)

        val invitation = assertNotNull(ReferralInvitation.parse(text))

        assertEquals(50, text.length)
        assertEquals(text, invitation.token)
        assertEquals(expiry, invitation.expiresAtSeconds)
    }

    @Test
    fun `WL-S40 surrounding whitespace is trimmed before the shape is read`() {
        val text = token(expiry)

        val invitation = assertNotNull(ReferralInvitation.parse("  \n$text\t "))

        assertEquals(text, invitation.token)
    }

    @Test
    fun `WL-S40 the expiry is read as an unsigned big-endian value`() {
        val high = 4_000_000_000L

        assertEquals(high, ReferralInvitation.parse(token(high))?.expiresAtSeconds)
    }

    @Test
    fun `WL-S40 the expiry second itself counts as expired`() {
        val invitation = assertNotNull(ReferralInvitation.parse(token(expiry)))

        assertFalse(invitation.isExpired(expiry - 1))
        assertTrue(invitation.isExpired(expiry))
        assertTrue(invitation.isExpired(expiry + 1))
    }

    @Test
    fun `WL-S40 a token that is not exactly the shape is not an invitation`() {
        val text = token(expiry)

        assertNull(ReferralInvitation.parse(text.dropLast(1)))
        assertNull(ReferralInvitation.parse(text + "A"))
        assertNull(ReferralInvitation.parse(text.replaceRange(0, 1, "+")))
        assertNull(ReferralInvitation.parse(text.replaceRange(0, 1, "=")))
        assertNull(ReferralInvitation.parse("$text $text"))
        assertNull(ReferralInvitation.parse("my order number is $text"))
    }

    @Test
    fun `WL-S40 the shape check does not verify the signature`() {
        // Any 37 bytes with a readable expiry pass. The server is the authority.
        assertNotNull(ReferralInvitation.parse(token(expiry, filler = 0)))
        assertNotNull(ReferralInvitation.parse(token(expiry, filler = 255)))
    }

    @Test
    fun `WL-S41 ordinary text is not an invitation`() {
        listOf("", "   ", "hello", "https://example.com/?a=b", "warplink").forEach {
            assertNull(ReferralInvitation.parse(it), it)
        }
    }
}
