package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.MatchType
import app.warplink.WarpLinkDeepLink
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class StorageCachedAttributionTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("warplink_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        storage = Storage(context)
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
        storage.isFirstLaunch = false
        storage.clearCachedAttribution()
        assertNull(storage.cachedAttribution)
        assertFalse(storage.isFirstLaunch)
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
