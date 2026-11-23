package app.warplink.internal

import android.app.Activity
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WL-S71: work waits for an activity on screen and is never dropped for waiting. */
@RunWith(RobolectricTestRunner::class)
class ForegroundGateTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val ran = mutableListOf<String>()

    private fun gate(onScreen: Boolean) = ForegroundGate { onScreen }.also { it.attach(application) }

    @Test
    fun `WL-S71 work runs at once while the app is on screen`() {
        gate(onScreen = true).whenVisible { ran.add("now") }

        assertEquals(listOf("now"), ran)
    }

    @Test
    fun `WL-S71 work called in the background waits for the first activity start`() {
        val gate = gate(onScreen = false)

        gate.whenVisible { ran.add("a") }
        gate.whenVisible { ran.add("b") }
        assertEquals(emptyList(), ran)

        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(listOf("a", "b"), ran, "queued work runs once, in call order")
    }

    @Test
    fun `WL-S71 work runs once however many activities start after it`() {
        val gate = gate(onScreen = false)
        gate.whenVisible { ran.add("a") }

        Robolectric.buildActivity(Activity::class.java).create().start()
        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `WL-S71 work called after every activity stopped waits for the next start`() {
        val gate = gate(onScreen = false)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start()
        activity.stop()

        gate.whenVisible { ran.add("later") }
        assertEquals(emptyList(), ran)

        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(listOf("later"), ran)
    }

    @Test
    fun `WL-S71 a second activity keeps the app on screen while the first stops`() {
        val gate = gate(onScreen = false)
        val first = Robolectric.buildActivity(Activity::class.java).create().start()
        Robolectric.buildActivity(Activity::class.java).create().start()
        first.stop()

        gate.whenVisible { ran.add("still on screen") }

        assertEquals(listOf("still on screen"), ran)
    }

    @Test
    fun `WL-S71 an app that was on screen before the SDK started is not waited for`() {
        val gate = gate(onScreen = true)

        gate.whenVisible { ran.add("now") }

        assertEquals(listOf("now"), ran)
    }

    @Test
    fun `WL-S71 the activity seen on screen at attach counts, so its stop puts the app in the background`() {
        val onScreen = Robolectric.buildActivity(Activity::class.java).create().start()
        val gate = gate(onScreen = true)

        onScreen.stop()
        gate.whenVisible { ran.add("later") }

        assertEquals(emptyList(), ran, "its only activity stopped, so it is in the background now")
    }

    @Test
    fun `WL-S71 a move from the activity seen at attach to a second one keeps the app on screen`() {
        val first = Robolectric.buildActivity(Activity::class.java).create().start()
        val gate = gate(onScreen = true)
        Robolectric.buildActivity(Activity::class.java).create().start()
        first.stop()

        gate.whenVisible { ran.add("still on screen") }

        assertEquals(listOf("still on screen"), ran)
    }

    @Test
    fun `WL-S71 the launch activity starting after a seeded attach does not count twice`() {
        val gate = gate(onScreen = true)
        val launch = Robolectric.buildActivity(Activity::class.java).create().start()

        launch.stop()
        gate.whenVisible { ran.add("later") }

        assertEquals(emptyList(), ran, "one activity started and stopped, so the app is in the background")
    }

    @Test
    fun `WL-S71 a stop with no start seen clears the seed and puts the app in the background`() {
        val onScreen = Robolectric.buildActivity(Activity::class.java).create().start()
        val gate = gate(onScreen = true)

        onScreen.stop()
        gate.whenVisible { ran.add("later") }
        assertEquals(emptyList(), ran)

        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(listOf("later"), ran)
    }

    @Test
    fun `WL-S71 only a plain foreground importance seeds the gate as on screen`() {
        fun seeds(importance: Int, reason: Int) = isOnScreen(
            RunningAppProcessInfo().also {
                it.importance = importance
                it.importanceReasonCode = reason
            }
        )

        assertTrue(seeds(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, RunningAppProcessInfo.REASON_UNKNOWN))
        assertFalse(
            seeds(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, RunningAppProcessInfo.REASON_PROVIDER_IN_USE),
            "a content provider in use keeps the process up with no activity"
        )
        assertFalse(
            seeds(RunningAppProcessInfo.IMPORTANCE_FOREGROUND, RunningAppProcessInfo.REASON_SERVICE_IN_USE),
            "a service in use keeps the process up with no activity"
        )
        assertFalse(seeds(RunningAppProcessInfo.IMPORTANCE_VISIBLE, RunningAppProcessInfo.REASON_UNKNOWN))
        assertFalse(seeds(RunningAppProcessInfo.IMPORTANCE_SERVICE, RunningAppProcessInfo.REASON_UNKNOWN))
    }

    @Test
    fun `detaching drops the work still waiting`() {
        val gate = gate(onScreen = false)
        gate.whenVisible { ran.add("dropped") }

        gate.detach()
        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(emptyList(), ran)
    }

    @Test
    fun `attaching the same application twice keeps one set of callbacks`() {
        val gate = gate(onScreen = false)
        gate.attach(application)
        gate.whenVisible { ran.add("a") }

        Robolectric.buildActivity(Activity::class.java).create().start()

        assertEquals(listOf("a"), ran)
    }
}
