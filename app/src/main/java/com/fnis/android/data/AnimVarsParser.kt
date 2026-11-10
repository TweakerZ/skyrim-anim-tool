package com.fnis.android.data

import com.fnis.android.model.AnimationGroup

data class BehaviorVars(
    val behaviorName: String,
    val numVars: Int,
    val variables: List<String>
)

object AnimVarsParser {

    fun parse(text: String): List<BehaviorVars> {
        val behaviors = mutableListOf<BehaviorVars>()
        var currentName: String? = null
        var currentNum = 0
        val currentVars = mutableListOf<String>()
        var inVars = false

        for (line in text.removePrefix("\uFEFF").lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!inVars && trimmed.contains(";")) {
                val parts = trimmed.split(";", limit = 2)
                if (parts.size >= 2) {
                    currentName = parts[0].trim()
                    currentNum = parts[1].trim().toIntOrNull() ?: 0
                    currentVars.clear()
                    inVars = true
                }
            } else if (inVars) {
                currentVars.add(trimmed)
                if (currentVars.size >= currentNum) {
                    behaviors.add(
                        BehaviorVars(currentName ?: "", currentNum, currentVars.toList())
                    )
                    inVars = false
                    currentName = null
                }
            }
        }
        return behaviors
    }
}
