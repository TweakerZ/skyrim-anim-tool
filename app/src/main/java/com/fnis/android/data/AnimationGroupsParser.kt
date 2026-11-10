package com.fnis.android.data

import com.fnis.android.model.AnimationGroup

object AnimationGroupsParser {

    fun parse(text: String): List<AnimationGroup> {
        val groups = mutableListOf<AnimationGroup>()
        var current: AnimationGroup? = null

        for (line in text.removePrefix("\uFEFF").lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("'")) {
                // '<groupName>\t<groupId> <numAnims>
                val parts = trimmed.substring(1).trim().split("\\s+".toRegex(), limit = 3)
                if (parts.size >= 3) {
                    val name = parts[0].removePrefix("_").trim()
                    val groupId = parts[1].toIntOrNull() ?: groups.size
                    val numAnims = parts[2].toIntOrNull() ?: 0
                    val group = AnimationGroup(groupId, name, numAnims, "", "")
                    current = group
                    groups.add(group)
                }
            } else {
                val group = current ?: continue
                // <hkx file> <flags>
                val parts = trimmed.split("\\s+".toRegex(), limit = 2)
                if (parts.size >= 2 && group.hkxFile.isEmpty()) {
                    val updated = group.copy(hkxFile = parts[0].trim(), flags = parts[1].trim())
                    current = updated
                    groups[groups.lastIndex] = updated
                }
            }
        }
        return groups
    }
}
