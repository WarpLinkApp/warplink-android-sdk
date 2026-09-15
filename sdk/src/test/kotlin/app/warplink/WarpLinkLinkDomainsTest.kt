package app.warplink

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.RetrySettings
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import kotlin.test.assertIs

/**
 * WL-S08 at the public surface: a link on a custom domain has to be claimed on
 * a first launch, when `/sdk/validate` has never answered.
 *
 * Every test here points the SDK at a dead port, so no server answer is
 * possible. Reaching the network at all ([WarpLinkError.NetworkError] rather
 * than [WarpLinkError.InvalidUrl]) is therefore the proof that the URI was
 * recognized from a locally available source.
 */
@RunWith(RobolectricTestRunner::class)
class WarpLinkLinkDomainsTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"

    private fun manualOptions() = WarpLinkOptions(
        apiEndpoint = "http://localhost:1",
        automaticDeepLinks = false,
        automaticDeferredDeepLinks = false
    )

    @Before
    fun setUp() {
        WarpLink.reset()
        // Every test here points at a dead port, so every resolve fails transiently
        // and is retried. The wait before a retry is a `postDelayed`, and nothing
        // below moves the clock, so without this the NetworkError these tests read as
        // proof would sit behind a timer that never comes due. What the waits are
        // worth is pinned in BoundedRetryTest, against a fake scheduler.
        WarpLink.retrySettingsOverride = RetrySettings.NO_WAIT
        Storage(ApplicationProvider.getApplicationContext()).clearAll()
    }

    @After
    fun tearDown() {
        // WarpLink is an object, so the override is process-wide and outlives this
        // class inside one Gradle test JVM. reset() clears it.
        WarpLink.reset()
    }

    @Test
    fun `WL-S08 a linkDomains custom domain resolves before validate answers`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            // Declared as a pasted URL, the shape a developer actually copies.
            manualOptions().copy(linkDomains = listOf("https://links.example.com/"))
        )

        assertIs<WarpLinkError.NetworkError>(
            resolveBlocking("https://links.example.com/abc123").exceptionOrNull()
        )
    }

    @Test
    fun `WL-S08 a manifest custom domain resolves before validate answers`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        setManifestDomains(ctx, "links.manifest.com")

        WarpLink.configure(ctx, validKey, manualOptions())

        assertIs<WarpLinkError.NetworkError>(
            resolveBlocking("https://links.manifest.com/abc123").exceptionOrNull()
        )
    }

    @Test
    fun `WL-S08 a cached validate domain and a declared one both resolve`() {
        // The effective set is a union of every source, so hydrating the cache
        // of a previous validate must not cost the host its own declaration,
        // nor the other way round.
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("cached_domains", """["links.cached.com"]""")
            .commit()

        WarpLink.configure(
            ctx,
            validKey,
            manualOptions().copy(linkDomains = listOf("links.declared.com"))
        )

        assertIs<WarpLinkError.NetworkError>(
            resolveBlocking("https://links.cached.com/abc123").exceptionOrNull()
        )
        assertIs<WarpLinkError.NetworkError>(
            resolveBlocking("https://links.declared.com/abc123").exceptionOrNull()
        )
    }

    @Test
    fun `an undeclared custom domain is still rejected`() {
        // The declaration is a list, not a switch: declaring one domain must
        // not start claiming every host the app is handed.
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions().copy(linkDomains = listOf("links.example.com"))
        )

        assertIs<WarpLinkError.InvalidUrl>(
            resolveBlocking("https://links.other.com/abc123").exceptionOrNull()
        )
    }

    @Test
    fun `the default domain still resolves with no declaration at all`() {
        WarpLink.configure(
            ApplicationProvider.getApplicationContext(),
            validKey,
            manualOptions()
        )

        assertIs<WarpLinkError.NetworkError>(
            resolveBlocking("https://aplnk.to/abc123").exceptionOrNull()
        )
    }

    private fun setManifestDomains(context: Context, value: String) {
        Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo
            ?.metaData = Bundle().apply {
                putString("app.warplink.DOMAINS", value)
            }
    }

    /** Run [url] through handleDeepLink and wait for its single callback. */
    private fun resolveBlocking(url: String): Result<WarpLinkDeepLink> {
        var result: Result<WarpLinkDeepLink>? = null
        val latch = CountDownLatch(1)
        WarpLink.handleDeepLink(Uri.parse(url)) { r ->
            result = r
            latch.countDown()
        }
        // A single idle() only runs what is due NOW, and the answer arrives from a
        // background thread through the main handler, so the looper has to be run
        // until it does.
        idleMainLooperUntil(latch)
        return result!!
    }
}
