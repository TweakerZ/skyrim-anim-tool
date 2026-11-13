package com.fnis.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ModPackageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun pex(payload: String): ByteArray =
        byteArrayOf(0xFA.toByte(), 0x57.toByte(), 0xC0.toByte(), 0xDE.toByte()) +
            payload.toByteArray()

    @Test
    fun packsDataRelativePathsOnly() {
        val source = temporaryFolder.newFolder("output")
        File(source, "FNIS_aa2.pex").writeBytes(pex("aa2"))
        File(source, "FNIS_Patches.txt").writeText("report")
        File(source, "0_master.hkx").writeBytes(byteArrayOf(0, 1, 2))

        val result = ModPackage.build(source, File(temporaryFolder.root, "mod.zip"))

        assertEquals(
            listOf("scripts/FNIS_aa2.pex", "tools/GenerateFNIS_for_Users/FNIS_Patches.txt"),
            result.entries,
        )
        assertEquals(listOf("0_master.hkx"), result.skipped)

        ZipFile(result.file).use { zip ->
            assertTrue(zip.getEntry("scripts/FNIS_aa2.pex") != null)
            assertTrue(zip.getEntry("tools/GenerateFNIS_for_Users/FNIS_Patches.txt") != null)
            assertFalse(zip.getEntry("0_master.hkx") != null)
            val text = zip.getInputStream(zip.getEntry("tools/GenerateFNIS_for_Users/FNIS_Patches.txt"))
                .bufferedReader().readText()
            assertEquals("report", text)
        }
    }

    @Test
    fun leavesOutTruncatedPapyrusFiles() {
        val source = temporaryFolder.newFolder("output")
        File(source, "FNIS_aa2.pex").writeText("not a pex")

        val result = ModPackage.build(source, File(temporaryFolder.root, "mod.zip"))

        assertTrue(result.entries.isEmpty())
        assertEquals(listOf("FNIS_aa2.pex"), result.skipped)
    }
}
