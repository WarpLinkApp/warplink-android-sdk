package app.warplink.internal.referral

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallJsonFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `delete reports success when both the record and the temporary file are gone`() {
        val file = InstallJsonFile(folder.root, "record.json")
        File(folder.root, "record.json").writeText("{}")
        File(folder.root, "record.json.tmp").writeText("{}")

        assertTrue(file.delete())
        assertFalse(File(folder.root, "record.json").exists())
        assertFalse(File(folder.root, "record.json.tmp").exists())
    }

    @Test
    fun `delete reports failure when the temporary file cannot be removed`() {
        val file = InstallJsonFile(folder.root, "record.json")
        File(folder.root, "record.json").writeText("{}")
        File(folder.root, "record.json.tmp").apply {
            mkdir()
            File(this, "held").writeText("x")
        }

        assertFalse(file.delete(), "a leftover temporary file must make the clear report failure")
        assertFalse(File(folder.root, "record.json").exists())
    }
}
