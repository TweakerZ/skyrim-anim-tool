package com.fnis.android.data

import com.fnis.android.model.FnisIni
import java.io.File

/**
 * Writes the active generator options into Skyrim/Data/FNIS.ini, which is the
 * file the FNIS pipeline (and this app) reads at generation time. Existing
 * comments and unrelated keys are preserved.
 */
object IniWriter {

    private val OPTION_KEYS = setOf("pscd", "instantexecute", "loadctdcalculation", "redirectfiles")

    fun write(dataDir: File, ini: FnisIni): File {
        val target = File(dataDir, "FNIS.ini")
        val source = when {
            target.isFile -> target.readText()
            File(dataDir, "FNIS.ini0").isFile -> File(dataDir, "FNIS.ini0").readText()
            else -> "'FNIS ini file\n"
        }

        val kept = source.removePrefix("\uFEFF").lines().filterNot { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith(";")) {
                false
            } else {
                val key = trimmed.substringBefore('=').trim().lowercase()
                trimmed.contains('=') && key in OPTION_KEYS
            }
        }

        val sb = StringBuilder()
        kept.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("' Written by Skyrim Anim Tool")
        sb.appendLine("PSCD=${if (ini.pscd) 1 else 0}")
        sb.appendLine("InstantExecute=${if (ini.instantExecute) 1 else 0}")
        sb.appendLine("LoadCTDCalculation=${if (ini.loadCtdCalculation) 1 else 0}")
        ini.redirectFiles?.takeIf { it.isNotBlank() }?.let { sb.appendLine("RedirectFiles=$it") }

        target.writeText(sb.toString())
        return target
    }
}
