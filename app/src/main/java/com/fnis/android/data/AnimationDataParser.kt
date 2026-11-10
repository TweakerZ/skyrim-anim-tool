package com.fnis.android.data

data class AnimationEntry(
    val name: String,
    val crc: Int,
    val referenceCrc: Int,
    val typeFlag: Int = 7891816
)

data class AnimationProject(
    val fileName: String,
    val animations: List<AnimationEntry>
)

object AnimationDataParser {

    fun parse(text: String): List<AnimationProject> {
        val projects = mutableListOf<AnimationProject>()
        val lines = text.lines().map { it.trim() }
        var i = 0

        if (i >= lines.size) return projects
        val projectCount = lines[i].toIntOrNull() ?: 0
        i++

        val projectNames = mutableListOf<String>()
        for (idx in 0 until projectCount) {
            if (i < lines.size) {
                projectNames.add(lines[i])
                i++
            }
        }

        while (i < lines.size) {
            if (lines[i].isEmpty()) {
                i++
                continue
            }
            val projectName = lines[i]
            i++
            if (i >= lines.size) break
            i++ // FullBody.txt
            if (i >= lines.size) break
            i++ // V3
            if (i >= lines.size) break
            i++ // 0
            if (i >= lines.size) break
            i++ // 0
            if (i >= lines.size) break
            i++ // 0
            if (i >= lines.size) break
            val animCount = lines[i].toIntOrNull() ?: 0
            i++

            val animations = mutableListOf<AnimationEntry>()
            for (idx in 0 until animCount) {
                if (i >= lines.size) break
                val animLine = lines[i]
                i++
                val animParts = animLine.split("\\s+".toRegex(), limit = 3)
                if (animParts.size >= 3) {
                    animations.add(
                        AnimationEntry(
                            name = animParts[0].trim(),
                            crc = animParts[1].trim().toIntOrNull() ?: 0,
                            referenceCrc = animParts[2].trim().toIntOrNull() ?: 0
                        )
                    )
                }
            }
            projects.add(AnimationProject(projectName, animations))
        }
        return projects
    }
}
