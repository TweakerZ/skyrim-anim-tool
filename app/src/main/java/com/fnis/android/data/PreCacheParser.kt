package com.fnis.android.data

import com.fnis.android.model.PreCacheEntry

object PreCacheParser {

    fun parse(text: String): List<PreCacheEntry> {
        val entries = mutableListOf<PreCacheEntry>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("'")) {
                i++
                continue
            }
            val modName = line.removePrefix("'").trim()
            i++
            if (i >= lines.size) break
            val referencePathCrc = lines[i].removePrefix("'").trim()
            i++
            if (i >= lines.size) break
            val referenceFileCrcLine = lines[i].removePrefix("'").trim()
            var appearances: String? = null
            if (referenceFileCrcLine.contains("/")) {
                val parts = referenceFileCrcLine.split("/", limit = 2)
                appearances = parts[1]
            }
            val referenceFileCrc = referenceFileCrcLine.split("/")[0]
            i++
            if (i >= lines.size) break
            val customPathCrc = lines[i].removePrefix("'").trim()
            i++
            if (i >= lines.size) break
            val numCustomAnims = lines[i].removePrefix("'").trim().toIntOrNull() ?: 0
            i++
            val customFileCrcs = mutableListOf<String>()
            var count = 0
            while (count < numCustomAnims && i < lines.size) {
                val crcLine = lines[i].removePrefix("'").trim()
                if (crcLine.isNotEmpty()) {
                    customFileCrcs.add(crcLine)
                    count++
                }
                i++
            }
            entries.add(
                PreCacheEntry(modName, referencePathCrc, referenceFileCrc, appearances, customPathCrc, numCustomAnims, customFileCrcs)
            )
        }
        return entries
    }
}
