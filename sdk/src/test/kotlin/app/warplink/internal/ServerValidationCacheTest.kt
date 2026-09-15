package app.warplink.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Deleting the domain cache write in performServerValidation kept the suite
 * green. The next offline cold launch would then resolve no custom-domain link.
 */
@RunWith(RobolectricTestRunner::class)
class ServerValidationCacheTest {

    private lateinit var context: Context
    private lateinit var storage: Storage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = Storage(context)
        storage.clearAll()
    }

    /**
     * A valid answer with domains writes them to UriParser, a Kotlin object
     * that outlives this class in a reused Robolectric sandbox. Put it back.
     */
    @After
    fun tearDown() = UriParser.resetKnownDomains()

    private fun validating(response: ValidateResponse): ApiKeyValidating =
        ApiKeyValidating { callback -> callback(Result.success(response)) }

    @Test
    fun `a valid answer caches the domains for the next offline launch`() {
        performServerValidation(
            storage = storage,
            apiClient = validating(ValidateResponse(valid = true, domains = listOf("go.acme.com"))),
            logger = null,
            onResult = { }
        )

        assertEquals(listOf("go.acme.com"), Storage(context).cachedDomains)

        // The mirror of the preserved direction asserted below: a guard that
        // skipped the write whenever a cache already existed would keep "an
        // empty answer keeps the cache" green while freezing the cache for the
        // life of the install. A real answer must land on top of an older one.
        // The first call cached the verdict; clear it so the second is not skipped.
        storage.apiKeyValidatedAt = null
        performServerValidation(
            storage = storage,
            apiClient = validating(ValidateResponse(valid = true, domains = listOf("moved.acme.com"))),
            logger = null,
            onResult = { }
        )

        assertEquals(listOf("moved.acme.com"), Storage(context).cachedDomains)
    }

    @Test
    fun `an empty answer does not clear a good cache`() {
        storage.cachedDomains = listOf("keep.me")

        performServerValidation(
            storage = storage,
            apiClient = validating(ValidateResponse(valid = true, domains = emptyList())),
            logger = null,
            onResult = { }
        )

        assertEquals(listOf("keep.me"), Storage(context).cachedDomains)
    }
}
