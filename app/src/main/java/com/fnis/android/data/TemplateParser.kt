package com.fnis.android.data

import com.fnis.android.model.BehaviorTemplate

object TemplateParser {

    fun parse(text: String): List<BehaviorTemplate> {
        val templates = mutableListOf<BehaviorTemplate>()
        for (line in text.removePrefix("\uFEFF").lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("'")) continue
            val parts = trimmed.split("\\s+".toRegex(), limit = 4)
            if (parts.size >= 4) {
                templates.add(
                    BehaviorTemplate(
                        fileName = parts[0].trim(),
                        typeFlag = parts[1].trim().toIntOrNull() ?: 0,
                        date = parts[2].trim(),
                        checksum = parts[3].trim().toLongOrNull() ?: 0L
                    )
                )
            }
        }
        return templates
    }
}
