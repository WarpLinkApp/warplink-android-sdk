package app.warplink.internal.referral

import org.junit.Test
import kotlin.test.assertEquals

class ReferrerQueryTest {

    @Test
    fun `splits pairs and keeps the first occurrence of a repeated key`() {
        val query = parseReferrerQuery("a=1&b=2&a=3")
        assertEquals(mapOf("a" to "1", "b" to "2"), query)
    }

    @Test
    fun `decodes each value after splitting so an encoded ampersand stays inside its value`() {
        val query = parseReferrerQuery("utm_source=warplink&utm_campaign=a%26b%3Dc")
        assertEquals("a&b=c", query["utm_campaign"])
        assertEquals("warplink", query["utm_source"])
    }

    @Test
    fun `decodes plus as a space inside a value`() {
        assertEquals("a b", parseReferrerQuery("k=a+b")["k"])
    }

    @Test
    fun `reads a referrer that arrived fully percent-encoded`() {
        val query = parseReferrerQuery("utm_source%3Dwarplink%26utm_content%3Dabc")
        assertEquals("warplink", query["utm_source"])
        assertEquals("abc", query["utm_content"])
    }

    @Test
    fun `ignores pairs without an equals sign and malformed escapes`() {
        assertEquals(emptyMap(), parseReferrerQuery("garbage&=x&%zz=1"))
    }

    @Test
    fun `blank input yields no pairs`() {
        assertEquals(emptyMap(), parseReferrerQuery("   "))
        assertEquals(emptyMap(), parseReferrerQuery(""))
    }
}
