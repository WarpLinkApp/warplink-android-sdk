package app.warplink.internal

import android.os.Looper
import app.warplink.WarpLinkError
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ApiClientTest {

    private val apiKey = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    private val baseURL = "http://localhost:1"

    @Test
    fun `constructor accepts apiKey and baseURL`() {
        val client = ApiClient(apiKey, baseURL)
        assertNotNull(client)
    }

    @Test
    fun `validateApiKey invokes callback with NetworkError`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<Boolean>? = null

        client.validateApiKey { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun `resolveLink invokes callback with NetworkError`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<LinkResponse>? = null

        client.resolveLink("test", "aplnk.to") { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun `matchAttribution invokes callback with NetworkError`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<AttributionResponse>? = null
        val signals = DeviceSignals(
            acceptLanguage = "en-US",
            screenWidth = 1080,
            screenHeight = 1920,
            timezoneOffset = -300,
            userAgent = "WarpLink-Android/0.1.0"
        )

        client.matchAttribution(signals, "0.1.0", null) { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun `matchAttribution with deviceId invokes callback`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<AttributionResponse>? = null
        val signals = DeviceSignals(
            acceptLanguage = "en-US",
            screenWidth = 1080,
            screenHeight = 1920,
            timezoneOffset = -300,
            userAgent = "WarpLink-Android/0.1.0"
        )

        client.matchAttribution(signals, "0.1.0", "device-123") { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun `matchAttribution with referrer and null signals invokes callback`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<AttributionResponse>? = null
        val linkId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

        client.matchAttribution(
            null, "0.1.0", null, referrer = linkId
        ) { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }

    @Test
    fun `matchAttribution with referrer and signals prefers referrer`() {
        val client = ApiClient(apiKey, baseURL)
        val latch = CountDownLatch(1)
        var result: Result<AttributionResponse>? = null
        val signals = DeviceSignals(
            acceptLanguage = "en-US",
            screenWidth = 1080,
            screenHeight = 1920,
            timezoneOffset = -300,
            userAgent = "WarpLink-Android/0.1.0"
        )
        val linkId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

        client.matchAttribution(
            signals, "0.1.0", null, referrer = linkId
        ) { r ->
            result = r
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(result)
        assertTrue(result!!.isFailure)
        assertIs<WarpLinkError.NetworkError>(result!!.exceptionOrNull())
    }
}
