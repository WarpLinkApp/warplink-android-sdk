package app.warplink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.warplink.internal.KeyEnvironment
import app.warplink.internal.Storage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android never reads the clipboard, so `readTether` has one answer. */
@RunWith(RobolectricTestRunner::class)
class TetherNoClipboardTest {

    private val key = "wl_test_abcdefghijklmnopqrstuvwxyz012345"
    private val api = LoopbackJsonServer("{}")
    private lateinit var context: Context

    @Before
    fun setUp() {
        WarpLink.reset()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        api.close()
        WarpLink.reset()
    }

    @Test
    fun `WL-S56 readTether answers NoClipboard once configured`() {
        configure()

        val result = readTether()

        assertEquals(TetherReadResult.NoClipboard, result.getOrNull())
    }

    @Test
    fun `WL-S56 readTether before configure fails with NotConfigured`() {
        val result = readTether()

        assertIs<WarpLinkError.NotConfigured>(result.exceptionOrNull())
    }

    @Test
    fun `WL-S56 readTether sends no request`() {
        configure()

        readTether()

        assertTrue(api.requestBodies.isEmpty())
    }

    @Test
    fun `WL-S56 no source file touches the clipboard`() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> FORBIDDEN.any { file.readText().contains(it) } }

        assertEquals(emptyList(), offenders.map { it.path }.toList())
    }

    @Test
    fun `WL-S56 the last test receipt is null until a test code is redeemed`() {
        assertNull(WarpLink.lastTetherTestReceipt)
        configure()
        assertNull(WarpLink.lastTetherTestReceipt)
    }

    private fun readTether(): Result<TetherReadResult> {
        var result: Result<TetherReadResult>? = null
        WarpLink.readTether { result = it }
        idleMainLooperUntilSet { result != null }
        return result!!
    }

    private fun idleMainLooperUntilSet(done: () -> Boolean) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        check(done()) { "readTether never answered" }
    }

    private fun configure() {
        Storage(context, KeyEnvironment.of(key)).apiKeyValidatedAt = System.currentTimeMillis()
        WarpLink.configure(
            context, key,
            WarpLinkOptions(
                apiEndpoint = api.baseUrl,
                automaticDeepLinks = false,
                automaticDeferredDeepLinks = false,
            ),
        )
    }

    private companion object {
        val FORBIDDEN = listOf("ClipboardManager", "CLIPBOARD_SERVICE", "primaryClip", "ClipData")
    }
}
