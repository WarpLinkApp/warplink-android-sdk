package app.warplink.internal

import app.warplink.LoopbackJsonServer
import app.warplink.idleMainLooperUntil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.assertNull

/**
 * The API answers an absent deep link with an explicit JSON `null`, not by
 * omitting the key: `android_url: link.androidUrl ?? null` on the resolve
 * route, and `deep_link_url` on both attribution branches.
 *
 * Android's `org.json` is AOSP libcore, not the json.org reference build, and
 * the two disagree on exactly this input: `optString` maps the `JSONObject.NULL`
 * sentinel through `String.valueOf`, which yields the 4-character String
 * "null". A non-empty String survives `ifEmpty`, `?:` and `?.let` alike, so the
 * documented `link.deepLinkUrl ?: link.destination` recipe would route the user
 * to the literal text "null".
 *
 * These tests pin the only thing a host can safely branch on: an explicit null
 * on the wire arrives as a Kotlin null.
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientJsonNullTest {

    private var api: LoopbackJsonServer? = null

    @After
    fun tearDown() {
        api?.close()
    }

    private fun clientFor(body: String): ApiClient {
        val server = LoopbackJsonServer(body).also { api = it }
        server.serveOnce()
        return ApiClient(API_KEY, server.baseUrl)
    }

    private fun resolveAgainst(body: String): LinkResponse {
        val done = CountDownLatch(1)
        var captured: LinkResponse? = null

        clientFor(body).resolveLink("abc123", "aplnk.to") { r ->
            captured = r.getOrNull()
            done.countDown()
        }
        // Idle the looper as the callback arrives rather than sleeping out a
        // fixed await: ApiClient posts to the main handler, which Robolectric
        // only runs when a test drives it.
        idleMainLooperUntil(done)
        return requireNotNull(captured) { "callback never delivered a response" }
    }

    private fun matchAgainst(body: String): AttributionResponse {
        val done = CountDownLatch(1)
        var captured: AttributionResponse? = null

        clientFor(body).matchAttribution(
            DeviceSignals("en-US", -300, "America/Toronto"), "1.1.0", null,
            isReinstall = false
        ) { r ->
            captured = r.getOrNull()
            done.countDown()
        }
        idleMainLooperUntil(done)
        return requireNotNull(captured) { "callback never delivered a response" }
    }

    @Test
    fun `WL-S17 an explicit null android_url resolves to a Kotlin null`() {
        val parsed = resolveAgainst(
            """{"id":"11111111-1111-1111-1111-111111111111","slug":"abc123",
                "destination_url":"https://example.com/landing",
                "ios_url":null,"android_url":null,"custom_params":{}}"""
        )

        // A web-only link is the default shape of a newly created link, so this
        // is the common response, not an edge case.
        assertNull(parsed.androidUrl)
        assertNull(parsed.iosUrl)
    }

    @Test
    fun `WL-S17 an omitted android_url still resolves to a Kotlin null`() {
        val parsed = resolveAgainst(
            """{"id":"11111111-1111-1111-1111-111111111111","slug":"abc123",
                "destination_url":"https://example.com/landing"}"""
        )

        assertNull(parsed.androidUrl)
        assertNull(parsed.iosUrl)
    }

    @Test
    fun `WL-S17 an explicit null deep_link_url on a match resolves to a Kotlin null`() {
        val parsed = matchAgainst(
            """{"matched":true,"match_type":"deterministic","match_confidence":1.0,
                "match_guaranteed":true,
                "link_id":"11111111-1111-1111-1111-111111111111",
                "destination_url":"https://example.com/landing",
                "deep_link_url":null,"install_id":null}"""
        )

        // The first launch after an install is the highest-value routing moment
        // the SDK has; "null" here is the string the host would navigate to.
        assertNull(parsed.deepLinkUrl)
        assertNull(parsed.installId)
    }

    @Test
    fun `WL-S17 an explicit null no-match leaves every optional field null`() {
        val parsed = matchAgainst(
            """{"matched":false,"match_type":null,"match_confidence":null,
                "link_id":null,"destination_url":null,"deep_link_url":null,
                "install_id":null}"""
        )

        assertNull(parsed.matchType)
        assertNull(parsed.linkId)
        assertNull(parsed.destinationUrl)
        assertNull(parsed.deepLinkUrl)
        assertNull(parsed.installId)
    }

    private companion object {
        const val API_KEY = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    }
}
