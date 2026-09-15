package app.warplink.internal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DeclaredLinkDomainsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        setManifestDomains(null)
        UriParser.resetKnownDomains()
    }

    @After
    fun tearDown() {
        // UriParser is a process-global singleton, and the manifest meta-data
        // is set on Robolectric's shared package info; leaking either would
        // change the answer of an unrelated test.
        setManifestDomains(null)
        UriParser.resetKnownDomains()
    }

    @Test
    fun `WL-S08 option domain is recognized without any server response`() {
        assertFalse(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))

        applyDeclaredLinkDomains(context, listOf("links.a.com"), null)

        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))
    }

    @Test
    fun `WL-S08 manifest domains are recognized without any server response`() {
        setManifestDomains("links.a.com,links.b.com")

        applyDeclaredLinkDomains(context, emptyList(), null)

        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.b.com/abc")))
    }

    @Test
    fun `option and manifest are unioned, not one overriding the other`() {
        setManifestDomains("links.manifest.com")

        applyDeclaredLinkDomains(context, listOf("links.option.com"), null)

        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.manifest.com/abc")))
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.option.com/abc")))
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://aplnk.to/abc")))
    }

    @Test
    fun `manifest entries are normalized and blanks dropped`() {
        setManifestDomains(" https://Links.A.com/ , , links.b.com ")

        applyDeclaredLinkDomains(context, emptyList(), null)

        // The reader splits and nothing else; normalization is what makes the
        // entries comparable to a URI host.
        assertEquals(3, readManifestLinkDomains(context, null).size)
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.b.com/abc")))
    }

    @Test
    fun `no declaration leaves the default domain alone`() {
        applyDeclaredLinkDomains(context, emptyList(), null)

        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://aplnk.to/abc")))
        assertFalse(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))
    }

    @Test
    fun `missing manifest meta-data reads as an empty list`() {
        assertEquals(emptyList(), readManifestLinkDomains(context, null))
    }

    @Test
    fun `reconfiguring without the option drops the previous declaration`() {
        applyDeclaredLinkDomains(context, listOf("links.a.com"), null)
        assertTrue(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))

        applyDeclaredLinkDomains(context, emptyList(), null)

        assertFalse(UriParser.isWarpLinkUri(Uri.parse("https://links.a.com/abc")))
    }

    private fun setManifestDomains(value: String?) {
        val info = shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo ?: return
        info.metaData = value?.let { Bundle().apply { putString(MANIFEST_DOMAINS_KEY, it) } }
    }
}
