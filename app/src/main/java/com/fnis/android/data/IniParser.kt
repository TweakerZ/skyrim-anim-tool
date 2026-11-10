package com.fnis.android.data

import com.fnis.android.model.FnisIni

object IniParser {

    fun parse(text: String): FnisIni {
        var pscd = false
        var redirectFiles: String? = null
        var instantExecute = false
        var loadCtdCalculation = true

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("'")) continue
            val eqIndex = trimmed.indexOf('=')
            if (eqIndex < 0) continue
            val key = trimmed.substring(0, eqIndex).trim().lowercase()
            val value = trimmed.substring(eqIndex + 1).trim()

            when (key) {
                "pscd" -> pscd = value == "1"
                "redirectfiles" -> redirectFiles = value
                "instantexecute" -> instantExecute = value == "1"
                "loadctdcalculation" -> loadCtdCalculation = value == "1"
            }
        }

        return FnisIni(pscd, redirectFiles, instantExecute, loadCtdCalculation)
    }
}
