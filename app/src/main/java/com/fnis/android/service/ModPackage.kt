package com.fnis.android.service

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs the generated files into a mod archive.
 *
 * Entries use Data-relative paths (`scripts/...`,
 * `tools/GenerateFNIS_for_Users/...`), which is what a mod manager unpacks into
 * the Skyrim Data folder. Behavior .hkx files and the animation data files stay
 * out for now: the generated versions are not identical to FNIS output yet, and
 * a wrong behavior crashes Skyrim on load.
 */
object ModPackage {

    data class Result(
        val file: File,
        val entries: List<String>,
        val skipped: List<String>,
    )

    fun entryPath(fileName: String): String? = when {
        fileName.endsWith(".pex", ignoreCase = true) -> "scripts/$fileName"
        fileName == "FNIS_Patches.txt" -> "tools/GenerateFNIS_for_Users/$fileName"
        else -> null
    }

    /**
     * Writes [sourceDir] into [zipFile]. Only files with a Data-relative
     * destination and a valid payload are packed; everything else is reported
     * in [Result.skipped].
     */
    fun build(sourceDir: File, zipFile: File): Result {
        val files = sourceDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()

        val entries = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        zipFile.parentFile?.mkdirs()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            for (file in files) {
                val path = entryPath(file.name)
                if (path == null || !isValidPayload(file)) {
                    skipped += file.name
                    continue
                }
                zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                entries += path
            }
        }
        return Result(zipFile, entries, skipped)
    }

    /**
     * Papyrus executables start with the pex magic 0xFA57C0DE. A truncated or
     * wrong file in the archive would install silently and break the game's
     * FNIS scripts, so it is left out.
     */
    private fun isValidPayload(file: File): Boolean {
        if (!file.name.endsWith(".pex", ignoreCase = true)) return true
        if (file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 0xFA.toByte() && header[1] == 0x57.toByte() &&
                header[2] == 0xC0.toByte() && header[3] == 0xDE.toByte()
        } catch (_: Exception) {
            false
        }
    }
}
