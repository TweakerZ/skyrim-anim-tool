package com.fnis.android.data

import com.fnis.android.model.PatchDefinition

object PatchListParser {

    fun parse(text: String): List<PatchDefinition> {
        val patches = mutableListOf<PatchDefinition>()
        for (line in text.removePrefix("\uFEFF").lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("<!--")) continue

            val parts = trimmed.split("#")
            if (parts.size < 5) continue

            val patchId = parts[0].trim()
            if (patchId.isEmpty()) continue

            patches.add(
                PatchDefinition(
                    patchId = patchId,
                    hidden = parts[1].trim().toIntOrNull() ?: 0,
                    numBones = parts[2].trim().toIntOrNull() ?: 0,
                    requiredBehaviorPattern = parts[3].trim(),
                    description = parts[4].trim(),
                    modInstallCheckFile = parts.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() },
                    ctdContribution = parts.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
                )
            )
        }
        return patches
    }
}
