package app.warplink.internal

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals

/**
 * WL-S71: an activity that was on screen before the SDK started stays on screen
 * while a dialog activity opens over it and closes, though the gate never saw
 * the first activity start and the dialog's stop empties its own count.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundGateDialogTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val ran = mutableListOf<String>()

    private fun activity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java)

    /** An activity A on screen, the SDK attached after it, and a dialog B opened over A and closed again. */
    private fun dialogOpenedAndClosedOverSeededActivity(): Pair<ForegroundGate, ActivityController<Activity>> {
        val seeded = activity().create().start().resume()
        val gate = ForegroundGate { true }.also { it.attach(application) }
        seeded.pause()
        val dialog = activity().create().start().resume()
        dialog.pause()
        seeded.resume()
        dialog.stop()
        return gate to seeded
    }

    @Test
    fun `WL-S71 a dialog over the activity seen at attach stops and the app stays on screen`() {
        val (gate, _) = dialogOpenedAndClosedOverSeededActivity()

        gate.whenVisible { ran.add("still on screen") }

        assertEquals(listOf("still on screen"), ran)
    }

    @Test
    fun `WL-S71 the activity seen at attach stopping after its dialog closed puts the app in the background`() {
        val (gate, seeded) = dialogOpenedAndClosedOverSeededActivity()

        seeded.pause().stop()
        gate.whenVisible { ran.add("later") }

        assertEquals(emptyList(), ran)
    }

    @Test
    fun `WL-S71 the launch activity resuming after its start does not count twice`() {
        val gate = ForegroundGate { true }.also { it.attach(application) }
        val launch = activity().create().start().resume()

        launch.pause().stop()
        gate.whenVisible { ran.add("later") }

        assertEquals(emptyList(), ran, "one activity started, resumed and stopped")
    }
}
