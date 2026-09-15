package app.warplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs

/**
 * Resolve refuses a password protected link with a 403 that carries no
 * destination and no platform URLs. The host has to be told which refusal it
 * was, so it can send the user to the short URL in a browser rather than
 * treating the link as dead or the key as rejected.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordProtectedLinkTest {

    private val validKey = "wl_live_abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var context: Context
    private val received = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private val stubs = mutableListOf<LoopbackJsonServer>()

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
        Storage(context).clearAll()
        received.clear()
    }

    @After
    fun tearDown() {
        stubs.forEach { it.close() }
        WarpLink.reset()
    }

    @Test
    fun `WL-S26 a password protected link reaches onLink as PasswordRequired`() {
        val stub = LoopbackJsonServer(REFUSAL_JSON, status = 403)
            .also { stubs.add(it) }
        stub.serveOnce()
        // A fresh validation cache would spend the stub's single answer on
        // /sdk/validate before the resolve request ever reached it.
        Storage(context).apiKeyValidatedAt = System.currentTimeMillis()

        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = stub.baseUrl,
                automaticDeepLinks = true,
                automaticDeferredDeepLinks = false,
                onLink = { result -> received.add(result) }
            )
        )
        WarpLink.onNewIntent(
            Intent().apply { data = Uri.parse("https://aplnk.to/secret") }
        )

        idleMainLooperUntil(message = "the refusal never reached onLink") {
            received.isNotEmpty()
        }
        assertIs<WarpLinkError.PasswordRequired>(
            received.first().exceptionOrNull()
        )
    }

    @Test
    fun `a 403 without the password code is still a rejected key`() {
        // A 403 is also how a rejected key answers. Only the body's code tells
        // the two apart, so a refusal without it keeps the older meaning
        // rather than accusing every 403 of being password protected.
        val stub = LoopbackJsonServer(FORBIDDEN_JSON, status = 403)
            .also { stubs.add(it) }
        stub.serveOnce()
        Storage(context).apiKeyValidatedAt = System.currentTimeMillis()

        WarpLink.configure(
            context, validKey,
            WarpLinkOptions(
                apiEndpoint = stub.baseUrl,
                automaticDeepLinks = true,
                automaticDeferredDeepLinks = false,
                onLink = { result -> received.add(result) }
            )
        )
        WarpLink.onNewIntent(
            Intent().apply { data = Uri.parse("https://aplnk.to/secret") }
        )

        idleMainLooperUntil(message = "the refusal never reached onLink") {
            received.isNotEmpty()
        }
        assertIs<WarpLinkError.InvalidApiKey>(received.first().exceptionOrNull())
    }

    private companion object {
        const val REFUSAL_JSON =
            """{"error":{"code":"PASSWORD_REQUIRED","message":"This link is password protected."}}"""
        const val FORBIDDEN_JSON =
            """{"error":{"code":"FORBIDDEN","message":"Invalid API key"}}"""
    }
}
