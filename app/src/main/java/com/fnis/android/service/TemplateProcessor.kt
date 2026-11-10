package com.fnis.android.service

import com.fnis.android.model.AnimationGroup
import com.fnis.android.model.FnisVersion
import com.fnis.android.model.PatchDefinition

class TemplateProcessor {

    private val markerRegex = Regex("<!--\\s*\\$#([A-Za-z0-9_]+)[^$]*\\$[^>]*-->")
    private val patchStartRegex = Regex("""^\s*<!--\s*\$\*([A-Za-z0-9_]+)\$\s*-->\s*$""")
    private val patchElseRegex = Regex("""^\s*<!--\s*\$\*\*\$\s*-->\s*$""")
    private val patchEndRegex = Regex("""^\s*<!--\s*\$\*\$\s*-->\s*$""")
    private val conditionStartRegex = Regex("""^\s*<!--\s*\$([^\$\#\*\^\-][^$]*)\$\s*-->\s*$""")
    private val conditionEndRegex = Regex("""^\s*<!--\s*\$\$\s*-->\s*$""")
    private val commentMarkerRegex = Regex("""^\s*<!--\s*COMMENT:.*-->\s*$""")
    private val anyMarkerRegex = Regex("""^\s*<!--\s*\$.*\$\s*-->\s*$""")
    private val expressionLineRegex = Regex("""^\s*\$([^$]+)\$\s*$""")

    // Multiply block directives (FNIS template control flow)
    private val multiplyStartRegex = Regex("""^\s*<!--\s*\$\^([^$]+)\$\s*-->\s*$""")
    private val multiplySubEndRegex = Regex("""^\s*<!--\s*\$\-\$\s*-->\s*$""")
    private val multiplyCondStartRegex = Regex("""^\s*<!--\s*\$\-([^$]+)\$\s*-->\s*$""")
    private val multiplyCondEndRegex = Regex("""^\s*<!--\s*\$\-\s*-->\s*$""")
    private val multiplyEndRegex = Regex("""^\s*<!--\s*\$\$\s*-->\s*$""")

    /**
     * Resolves FNIS's patch-conditional blocks:
     *
     *   <!-- $*xp32_3$ -->
     *   ...used when the patch is enabled...
     *   <!-- $**$ -->
     *   ...used when the patch is disabled...
     *   <!-- $*$ -->
     *
     * Blocks without the `$**$` separator simply disappear when the patch is
     * disabled.
     */
    fun resolvePatchConditionals(text: String, enabledIds: Set<String>): String {
        val lines = text.lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val start = patchStartRegex.matchEntire(line)
            if (start == null) {
                out.add(line)
                i++
                continue
            }

            val id = start.groupValues[1]
            i++
            val enabled = mutableListOf<String>()
            val disabled = mutableListOf<String>()
            var current = enabled
            while (i < lines.size) {
                val next = lines[i]
                if (patchElseRegex.matches(next)) {
                    current = disabled
                    i++
                    continue
                }
                if (patchEndRegex.matches(next)) {
                    i++
                    break
                }
                current.add(next)
                i++
            }
            out.addAll(if (id in enabledIds) enabled else disabled)
        }
        return out.joinToString("\n")
    }

    /**
     * Processes the behavior XML templates: patch conditionals, multiply blocks,
     * and FNIS AA placeholders. The remaining `$...$` markers are alternate
     * animation expressions that need installed mod data, which is not
     * available to the app yet; they are stripped as comments.
     */
    fun processTemplate(
        templateXml: String,
        behaviorName: String,
        appliedPatches: List<PatchDefinition>,
        groups: List<AnimationGroup>,
        enabledIds: Set<String>
    ): String {
        var result = resolvePatchConditionals(templateXml, enabledIds)
        result = processConditionalBlocks(result, enabledIds)
        result = processMultiplyBlocks(result, enabledIds)
        result = markerRegex.replace(result) { match ->
            val id = match.groupValues[1]
            when {
                id.equals("FNIS_AA", ignoreCase = true) ->
                    "<!-- FNIS AA groups: ${groups.size} -->"
                id.startsWith("_") ->
                    "<!-- FNIS internal marker: $id -->"
                else ->
                    "<!-- FNIS template marker: $id -->"
            }
        }

        val key = behaviorKey(behaviorName)
        val targeted = appliedPatches.filter { key in behaviorNamesForMask(it.requiredBehaviorPattern) }
        val global = appliedPatches.filter { it.requiredBehaviorPattern.isBlank() }

        val patchBlock = buildString {
            append("<!-- FNIS patches applied to ").append(behaviorName).append(": ")
            append(if (targeted.isEmpty()) "none" else targeted.joinToString(", ") { it.patchId })
            append(" -->\n")
            append("<!-- FNIS global patches: ")
            append(if (global.isEmpty()) "none" else global.joinToString(", ") { it.patchId })
            append(" -->\n")
        }

        val declaration = result.indexOf("<?xml")
        if (declaration >= 0) {
            val lineEnd = result.indexOf('\n', declaration)
                .let { if (it < 0) result.length else it + 1 }
            result = result.substring(0, lineEnd) + patchBlock + result.substring(lineEnd)
        } else {
            result = patchBlock + result
        }

        return result
    }

    /**
     * Processes FNIS template multiply blocks and conditionals:
     *
     *   <!-- $^expression$ -->    start multiply block (repeat N times)
     *   ...block content...
     *   <!-- $$ -->               end multiply block
     *
     * Within a multiply block:
     *   <!-- $--$ -->              end sub-block (restore conditional state)
     *   <!-- $-expression$ -->     start conditional (skip if expression false)
     *   ...conditional content...
     *   <!-- $- -->                end conditional
     *
     *   <!-- $RI/N$ -->            record index increment directive
     *
     * Blocks are repeated N times. Within each repetition, $-aa?.group$ conditionals
     * skip content when the named AA group has no animations. Since the app does not
     * scan installed mod animation lists, unknown expressions evaluate to 0 and
     * AA groups are treated as empty.
     */
    fun processMultiplyBlocks(text: String, enabledIds: Set<String>): String {
        val lines = text.lines()
        val out = mutableListOf<String>()
        processMultiplyBlockLines(lines, 0, lines.size, enabledIds, out)
        return out.joinToString("\n")
    }

    private fun processMultiplyBlockLines(
        lines: List<String>,
        start: Int,
        end: Int,
        enabledIds: Set<String>,
        out: MutableList<String>,
        depth: Int = 0,
    ) {
        var i = start
        while (i < end) {
            val line = lines[i]

            // End of current block level. Inside a multiply block this returns
            // to the caller. At top level the line belongs to a conditional
            // block, so keep it for the conditional pass instead of dropping
            // the marker it needs to find its end.
            if (multiplyEndRegex.matchEntire(line) != null) {
                if (depth > 0) return
                out.add(line)
                i++
                continue
            }

            // Start of multiply block: <!-- $^expression$ -->
            val ms = multiplyStartRegex.matchEntire(line)
            if (ms != null) {
                val expression = ms.groupValues[1]
                val count = evaluate(expression, enabledIds).toInt().coerceAtLeast(0)
                // Find the matching $$ end
                val blockEnd = findMultiplyBlockEnd(lines, i + 1, end)
                if (blockEnd < 0) {
                    out.add(line)
                    i++
                    continue
                }
                val blockLines = lines.subList(i + 1, blockEnd)
                for (rep in 0 until count) {
                    processMultiplyBlockLines(blockLines, 0, blockLines.size, enabledIds, out, depth + 1)
                }
                i = blockEnd + 1
                continue
            }

            // Multiply sub-block end: <!-- $--$ --> (no-op in simplified processing)
            if (multiplySubEndRegex.matchEntire(line) != null) {
                i++
                continue
            }

            // Multiply conditional start: <!-- $-expression$ -->
            val mcs = multiplyCondStartRegex.matchEntire(line)
            if (mcs != null) {
                val expression = mcs.groupValues[1]
                val enabled = evaluate(expression, enabledIds) != 0L
                // Find the matching $-
                val condEnd = findMultiplyCondEnd(lines, i + 1, end)
                if (condEnd < 0) {
                    out.add(line)
                    i++
                    continue
                }
                if (enabled) {
                    processMultiplyBlockLines(lines, i + 1, condEnd, enabledIds, out, depth + 1)
                }
                i = condEnd + 1
                continue
            }

            // Multiply conditional end: <!-- $- --> (no-op)
            if (multiplyCondEndRegex.matchEntire(line) != null) {
                i++
                continue
            }

            // Patch conditionals within multiply blocks
            val ps = patchStartRegex.matchEntire(line)
            if (ps != null) {
                val id = ps.groupValues[1]
                val patchEnabled = id in enabledIds
                // Find the matching $*$ end (with optional $**$ else)
                val patchBlockEnd = findPatchBlockEnd(lines, i + 1, end)
                if (patchBlockEnd < 0) {
                    out.add(line)
                    i++
                    continue
                }
                val elseIdx = findPatchElse(lines, i + 1, patchBlockEnd)
                if (patchEnabled) {
                    // Output enabled branch, skip disabled branch
                    val blockEnd = if (elseIdx >= 0) elseIdx else patchBlockEnd
                    processMultiplyBlockLines(lines, i + 1, blockEnd, enabledIds, out, depth + 1)
                } else if (elseIdx >= 0) {
                    // Output disabled branch
                    processMultiplyBlockLines(lines, elseIdx + 1, patchBlockEnd, enabledIds, out, depth + 1)
                }
                // else: patch disabled and no else branch -> skip entirely
                i = patchBlockEnd + 1
                continue
            }

            // Regular line
            out.add(line)
            i++
        }
    }

    /**
     * Finds the line index of the matching <!-- $$ --> end of a multiply block.
     * Accounts for nested multiply blocks.
     */
    private fun findMultiplyBlockEnd(lines: List<String>, start: Int, end: Int): Int {
        var depth = 1
        var i = start
        while (i < end) {
            val line = lines[i]
            if (multiplyStartRegex.matchEntire(line) != null) {
                depth++
            } else if (multiplyEndRegex.matchEntire(line) != null) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /**
     * Finds the line index of the matching <!-- $- --> end of a multiply conditional.
     */
    private fun findMultiplyCondEnd(lines: List<String>, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val line = lines[i]
            if (multiplyCondEndRegex.matchEntire(line) != null) return i
            // Nested conditionals or multiply blocks would complicate this; skip for now
            i++
        }
        return -1
    }

    /**
     * Finds the line index of the matching <!-- $*$ --> end of a patch block.
     */
    private fun findPatchBlockEnd(lines: List<String>, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val line = lines[i]
            if (patchEndRegex.matchEntire(line) != null) return i
            i++
        }
        return -1
    }

    /**
     * Finds the line index of the <!-- $**$ --> else separator within a patch block.
     */
    private fun findPatchElse(lines: List<String>, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val line = lines[i]
            if (patchElseRegex.matchEntire(line) != null) return i
            i++
        }
        return -1
    }

    /**
     * Processes animationdatasinglefile.txt / animationsetdatasinglefile.txt.
     * These templates carry real patch data in conditional blocks, so this
     * path is fully resolved and validated against the FNIS baseline output.
     */
    fun processAnimationDataTemplate(text: String, enabledIds: Set<String>): String {
        var result = resolvePatchConditionals(text, enabledIds)
        result = processMultiplyBlocks(result, enabledIds)
        result = processConditionalBlocks(result, enabledIds)
        result = replaceExpressions(result, enabledIds)
        result = result.lines()
            .filterNot { commentMarkerRegex.matches(it) }
            .filterNot { anyMarkerRegex.matches(it) }
            .dropWhile { it.startsWith("<!-- FNIS template") }
            .joinToString("\n")
        return result
    }

    private fun processConditionalBlocks(text: String, enabledIds: Set<String>): String {
        val lines = text.lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val start = conditionStartRegex.matchEntire(line)
            if (start == null) {
                out.add(line)
                i++
                continue
            }

            val condition = start.groupValues[1]
            i++
            val block = mutableListOf<String>()
            while (i < lines.size && !conditionEndRegex.matches(lines[i])) {
                block.add(lines[i])
                i++
            }
            if (i < lines.size) i++

            if (evaluate(condition, enabledIds) != 0L) {
                out.addAll(block)
            }
        }
        return out.joinToString("\n")
    }

    private fun replaceExpressions(text: String, enabledIds: Set<String>): String {
        return text.lines().mapNotNull { line ->
            val match = expressionLineRegex.matchEntire(line)
            if (match == null) {
                line
            } else {
                val value = evaluate(match.groupValues[1], enabledIds)
                // A directive that resolves to zero produces no line at all.
                // Emitting "0" would shift every following record in the
                // animation data file and crash the engine while loading it.
                if (value == 0L) null else value.toString()
            }
        }.joinToString("\n")
    }

    /**
     * Small arithmetic evaluator for FNIS template expressions. Tokens that
     * refer to installed mods (mra/mrd/pa/km/faa and friends) are zero because
     * the app does not scan mod animation lists yet. `??patch` is 1 when that
     * patch is enabled.
     */
    fun evaluate(expression: String, enabledIds: Set<String>): Long {
        val cleaned = expression.trim().removePrefix("?")
        val parser = ExpressionParser(cleaned, enabledIds)
        return try {
            parser.parse()
        } catch (_: Exception) {
            0L
        }
    }

    private class ExpressionParser(
        private val input: String,
        private val enabledIds: Set<String>
    ) {
        private var pos = 0

        fun parse(): Long {
            val value = parseAdditive()
            return value
        }

        private fun parseAdditive(): Long {
            var value = parseMultiplicative()
            while (true) {
                skipSpace()
                when {
                    consume('+') -> value += parseMultiplicative()
                    consume('-') -> value -= parseMultiplicative()
                    else -> return value
                }
            }
        }

        private fun parseMultiplicative(): Long {
            var value = parsePrimary()
            while (true) {
                skipSpace()
                when {
                    consume('*') -> value *= parsePrimary()
                    consume('/') -> {
                        val divisor = parsePrimary()
                        value = if (divisor == 0L) 0L else value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parsePrimary(): Long {
            skipSpace()
            if (consume('(')) {
                val value = parseAdditive()
                skipSpace()
                consume(')')
                return value
            }
            if (consume('?')) {
                if (consume('?')) {
                    val id = readIdentifier()
                    return if (id in enabledIds) 1L else 0L
                }
                // Unknown conditional token.
                readIdentifier()
                return 0L
            }
            if (peek()?.isDigit() == true) {
                val start = pos
                while (peek()?.isDigit() == true) pos++
                return input.substring(start, pos).toLongOrNull() ?: 0L
            }
            val id = readIdentifier()
            return tokenValue(id)
        }

        private fun tokenValue(id: String): Long {
            if (id.isEmpty()) return 0L
            if (id.equals("faa", ignoreCase = true) || id.equals("pa", ignoreCase = true) ||
                id.equals("km", ignoreCase = true) || id.equals("f", ignoreCase = true)
            ) {
                return 0L
            }
            // mra25, mrd25, aan._sneakmt, aaT..., Epa, PAH, etc. all need
            // installed mod data and are zero without it.
            return 0L
        }

        private fun readIdentifier(): String {
            skipSpace()
            val start = pos
            while (pos < input.length) {
                val c = input[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '.') pos++ else break
            }
            return input.substring(start, pos)
        }

        private fun skipSpace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = if (pos < input.length) input[pos] else null

        private fun consume(c: Char): Boolean {
            if (peek() == c) {
                pos++
                return true
            }
            return false
        }
    }

    /**
     * PatchList uses different names for the two animation data files than the
     * template file names.
     */
    private fun behaviorKey(behaviorName: String): String = when (behaviorName) {
        "animationdatasinglefile" -> "animationdatasinglefile.txt"
        "animationsetdatasinglefile" -> "animationsetdatasinglefile"
        else -> behaviorName
    }

    fun behaviorNamesForMask(pattern: String): List<String> {
        if (pattern.isBlank()) return emptyList()
        val names = mutableListOf<String>()
        val reversed = pattern.reversed()
        for ((charIndex, c) in reversed.withIndex()) {
            val value = Character.digit(c, 16)
            if (value < 0) continue
            for (bit in 0..3) {
                if ((value shr bit) and 1 == 1) {
                    names += behaviorBitName(charIndex * 4 + bit)
                }
            }
        }
        return names
    }

    fun patchVersionPex(
        template: ByteArray,
        version: String = FnisVersion.STRING
    ): ByteArray {
        val old = FnisVersion.STRING.toByteArray(Charsets.US_ASCII)
        val new = version.toByteArray(Charsets.US_ASCII)
        if (new.size != old.size) return template.copyOf()

        val out = template.copyOf()
        var i = 0
        while (i <= out.size - old.size) {
            var match = true
            for (j in old.indices) {
                if (out[i + j] != old[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                for (j in new.indices) out[i + j] = new[j]
                i += old.size
            } else {
                i++
            }
        }
        return out
    }

    private fun behaviorBitName(bit: Int): String = when (bit) {
        0 -> "weapequip"
        1 -> "staggerbehavior"
        2 -> "sprintbehavior"
        3 -> "shoutmounted_behavior"
        4 -> "shout_behavior"
        5 -> "mt_behavior"
        6 -> "magicmountedbehavior"
        7 -> "magicbehavior"
        8 -> "idlebehavior"
        9 -> "horsebehavior"
        10 -> "crossbow_direction_behavior"
        11 -> "bow_direction_behavior"
        12 -> "blockbehavior"
        13 -> "bashbehavior"
        14 -> "1hm_locomotion"
        15 -> "1hm_behavior"
        16 -> "0_master"
        17 -> "a(s)dsf"
        18 -> "animationsetdatasinglefile"
        19 -> "animationdatasinglefile.txt"
        else -> "bit_$bit"
    }
}
