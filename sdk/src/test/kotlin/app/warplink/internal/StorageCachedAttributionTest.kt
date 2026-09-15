package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StorageCachedAttributionTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    @Test
    fun testCachedAttributionDefaultsToNull() {
        assertNull(storage.cachedAttribution)
    }

    @Test
    fun testRoundTripSerialization() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link1",
            destination = "https://example.com",
            deepLinkUrl = "myapp://path",
            customParams = mapOf("key" to "value"),
            isDeferred = true,
            matchType = MatchType.PROBABILISTIC,
            matchConfidence = 0.85
        )
        storage.cachedAttribution = deepLink
        val cached = storage.cachedAttribution

        assertEquals("link1", cached?.linkId)
        assertEquals("https://example.com", cached?.destination)
        assertEquals("myapp://path", cached?.deepLinkUrl)
        assertEquals(mapOf("key" to "value"), cached?.customParams)
        assertEquals(true, cached?.isDeferred)
        assertEquals(MatchType.PROBABILISTIC, cached?.matchType)
        assertEquals(0.85, cached?.matchConfidence)
    }

    @Test
    fun testNullDeepLinkUrl() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link2",
            destination = "https://example.com",
            deepLinkUrl = null,
            isDeferred = true
        )
        storage.cachedAttribution = deepLink
        assertNull(storage.cachedAttribution?.deepLinkUrl)
    }

    @Test
    fun testEmptyCustomParams() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link3",
            destination = "https://example.com",
            customParams = emptyMap(),
            isDeferred = true
        )
        storage.cachedAttribution = deepLink
        assertEquals(emptyMap(), storage.cachedAttribution?.customParams)
    }

    @Test
    fun testMatchTypeDeterministic() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link4",
            destination = "https://example.com",
            isDeferred = true,
            matchType = MatchType.DETERMINISTIC
        )
        storage.cachedAttribution = deepLink
        assertEquals(MatchType.DETERMINISTIC, storage.cachedAttribution?.matchType)
    }

    @Test
    fun testMatchTypeProbabilistic() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link5",
            destination = "https://example.com",
            isDeferred = true,
            matchType = MatchType.PROBABILISTIC
        )
        storage.cachedAttribution = deepLink
        assertEquals(MatchType.PROBABILISTIC, storage.cachedAttribution?.matchType)
    }

    @Test
    fun testNullMatchType() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link6",
            destination = "https://example.com",
            isDeferred = true,
            matchType = null
        )
        storage.cachedAttribution = deepLink
        assertNull(storage.cachedAttribution?.matchType)
    }

    @Test
    fun testNullMatchConfidence() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link7",
            destination = "https://example.com",
            isDeferred = true,
            matchConfidence = null
        )
        storage.cachedAttribution = deepLink
        assertNull(storage.cachedAttribution?.matchConfidence)
    }

    @Test
    fun testClearAllResetsCachedAttribution() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link8",
            destination = "https://example.com",
            isDeferred = true
        )
        storage.cachedAttribution = deepLink
        storage.clearAll()
        assertNull(storage.cachedAttribution)
    }

    @Test
    fun testClearCachedAttributionOnly() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link9",
            destination = "https://example.com",
            isDeferred = true
        )
        storage.cachedAttribution = deepLink
        storage.deferredCheckCompleted = true
        storage.clearCachedAttribution()
        assertNull(storage.cachedAttribution)
        assertTrue(storage.deferredCheckCompleted)
    }

    @Test
    fun testDeferredCheckFlagsDefaultFalse() {
        assertFalse(storage.deferredCheckAttempted)
        assertFalse(storage.deferredCheckCompleted)
    }

    @Test
    fun testDeferredCheckFlagsAreIndependent() {
        // "attempted" tracks that a check started; "completed" is consumed only
        // on a definitive server response. A failed attempt sets the former but
        // not the latter, so the next launch retries.
        storage.deferredCheckAttempted = true
        assertTrue(storage.deferredCheckAttempted)
        assertFalse(storage.deferredCheckCompleted)

        storage.deferredCheckCompleted = true
        assertTrue(storage.deferredCheckCompleted)
    }

    /**
     * Android reports equal install timestamps for a fresh install and a later
     * `lastUpdateTime` for an in-place upgrade. Storage reads them once, at
     * construction, so set them before building the instance under test.
     */
    private fun setInstallTimes(firstInstall: Long, lastUpdate: Long) {
        val info = Shadows.shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = firstInstall
        info.lastUpdateTime = lastUpdate
    }

    private fun writeLegacyConsumedFlag() {
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_first_launch", false).commit()
    }

    @Test
    fun `WL-S15 a restored 1_0_x flag never closes the gate, fresh install or upgrade`() {
        // Auto Backup restores SharedPreferences, so both a fresh install and an
        // in-place upgrade can arrive carrying a consumed 1.0.x flag. It proved
        // only that a check began. Neither shape may close the gate: doing so
        // skipped attribution permanently for whoever carried it.
        storage.clearAll()
        writeLegacyConsumedFlag()
        setInstallTimes(firstInstall = 1_000L, lastUpdate = 1_000L)
        assertFalse(Storage(context).deferredCheckCompleted)
        setInstallTimes(firstInstall = 1_000L, lastUpdate = 5_000L)
        assertFalse(Storage(context).deferredCheckCompleted)
    }

    @Test
    fun testDeferredFlagsSurviveReinstantiation() {
        storage.deferredCheckCompleted = true
        assertTrue(Storage(context).deferredCheckCompleted)
    }

    @Test
    fun testCachedDomainsRoundTrip() {
        storage.cachedDomains = listOf("aplnk.to", "links.example.com")
        assertEquals(
            listOf("aplnk.to", "links.example.com"),
            Storage(context).cachedDomains
        )
    }

    @Test
    fun testMalformedJsonReturnsNull() {
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().putString("cached_attribution", "not valid json{{{").commit()
        assertNull(storage.cachedAttribution)
    }

    @Test
    fun testCachedResultSurvivesReinstantiation() {
        val deepLink = WarpLinkDeepLink(
            linkId = "link10",
            destination = "https://example.com",
            deepLinkUrl = "myapp://test",
            isDeferred = true,
            matchType = MatchType.DETERMINISTIC,
            matchConfidence = 0.95
        )
        storage.cachedAttribution = deepLink

        val newStorage = Storage(context)
        val cached = newStorage.cachedAttribution
        assertEquals("link10", cached?.linkId)
        assertEquals("https://example.com", cached?.destination)
        assertEquals("myapp://test", cached?.deepLinkUrl)
        assertEquals(MatchType.DETERMINISTIC, cached?.matchType)
        assertEquals(0.95, cached?.matchConfidence)
    }
}
