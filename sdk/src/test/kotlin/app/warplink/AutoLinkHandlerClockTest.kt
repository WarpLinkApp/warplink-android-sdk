package app.warplink

import android.net.Uri
import app.warplink.internal.AutoLinkHandler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The dedupe window measured elapsed time with `System.currentTimeMillis()`,
 * which is wall clock and can move backwards: NITZ or NTP corrects a drifted
 * phone, the user edits the date, or a timezone database update lands. After a
 * backward step the subtraction goes negative, every negative value reads as
 * "inside the window", and the handler drops every repeat tap on the same link
 * until the clock catches up to where it was.
 *
 * A dropped tap here is silent and looks like the link is broken, so the window
 * must be measured with a source that cannot run backwards.
 */
@RunWith(RobolectricTestRunner::class)
class AutoLinkHandlerClockTest {

    private val uri: Uri = Uri.parse("https://aplnk.to/abc123")
    private val dispatched = CopyOnWriteArrayList<Result<WarpLinkDeepLink>>()
    private var resolveCount = 0
    private var clock = 10_000L

    private val handler = AutoLinkHandler(
        application = null,
        logger = null,
        isWarpLinkUri = { true },
        onLink = { result -> dispatched.add(result) },
        resolve = { _, _ -> resolveCount++ },
        now = { clock }
    )

    @Test
    fun `WL-S22 a backward clock step does not suppress a later tap`() {
        handler.dispatch(uri)
        assertEquals(1, resolveCount)

        // The correction: 9 seconds backwards, far outside the 1.5s window.
        clock = 1_000L
        handler.dispatch(uri)

        // Without a monotonic source this is still 1: the negative elapsed
        // reads as "inside the window" and the tap is dropped.
        assertEquals(2, resolveCount)
    }

    @Test
    fun `WL-S22 the dedupe window still holds when the clock moves normally`() {
        handler.dispatch(uri)
        clock += 500L
        handler.dispatch(uri)

        // Guards the fix from overshooting into "never dedupe".
        assertEquals(1, resolveCount)
    }

    @Test
    fun `a tap past the window is dispatched again`() {
        handler.dispatch(uri)
        clock += 2_000L
        handler.dispatch(uri)

        assertEquals(2, resolveCount)
    }
}
