package app.warplink.internal.referral

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SDK takes invitation text only from an explicit call, never from the clipboard. */
class NoClipboardAccessTest {

    @Test
    fun `WL-S47 no SDK source reads the system clipboard`() {
        val sources = File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(sources.isNotEmpty(), "the source scan found no files")

        val offenders = sources.filter { file ->
            val text = file.readText()
            FORBIDDEN.any { text.contains(it) }
        }

        assertEquals(emptyList(), offenders.map { it.path })
    }

    private companion object {
        val FORBIDDEN = listOf(
            "ClipboardManager",
            "CLIPBOARD_SERVICE",
            "primaryClip",
            "ClipData",
            "getPrimaryClip",
        )
    }
}
