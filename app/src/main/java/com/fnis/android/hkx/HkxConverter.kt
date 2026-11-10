package com.fnis.android.hkx

/**
 * Converts hkpackfile XML (as produced by FNIS templates) to a binary Havok
 * .hkx packfile, Skyrim LE format.
 *
 * Usage:
 *   val xml = templateFile.readText()
 *   val result = HkxConverter.convert(xml)
 *   val hkxBytes = result.hkx
 *   val errors = result.errors
 *
 * [ConversionResult.validation] describes whether the graph that was written is
 * structurally complete. Skyrim T-poses (and can lose meshes) when a behavior
 * references objects that are not in the file, so callers must not ship bytes
 * whose validation is not [ValidationReport.isValid].
 */
class HkxConverter {

    /**
     * Structural health of a converted behavior graph.
     *
     * A dangling reference makes the whole behavior fail to load in Skyrim,
     * which drops the character to a T-pose. Classless objects cannot be laid
     * out by the packfile writer, and leftover `$...$` text means a template
     * directive was never resolved.
     */
    data class ValidationReport(
        val reachableObjects: Int,
        val missingRefs: List<String>,
        val unresolvedClasses: Int,
        val placeholderValues: Int,
    ) {
        val isValid: Boolean
            get() = missingRefs.isEmpty() && unresolvedClasses == 0 && placeholderValues == 0

        fun summary(): String {
            if (isValid) return "ok ($reachableObjects objects)"
            val reasons = mutableListOf<String>()
            if (missingRefs.isNotEmpty()) {
                reasons += "${missingRefs.size} missing refs (${missingRefs.take(3).joinToString(", ")})"
            }
            if (unresolvedClasses > 0) reasons += "$unresolvedClasses objects without a class"
            if (placeholderValues > 0) reasons += "$placeholderValues unresolved template values"
            return reasons.joinToString("; ")
        }
    }

    data class ConversionResult(
        val hkx: ByteArray?,
        val errors: List<String>,
        val validation: ValidationReport? = null,
    )

    private val registry = HkClassRegistry

    fun convert(xml: String): ConversionResult {
        val errors = mutableListOf<String>()

        // Strip FNIS template directive markers that haven't been resolved yet.
        // The caller (FnisTemplateEngine) should resolve $...$ directives before
        // calling convert(). Any remaining <!-- $...$ --> lines are stripped.
        val cleanedXml = stripUnresolvedMarkers(xml)

        val parser = HkXmlParser()
        val parseResult = parser.parse(cleanedXml)

        val topLevel = parseResult.topLevel
        if (topLevel == null) {
            errors.add("No top-level object found in XML")
            return ConversionResult(null, errors)
        }

        val allObjects = collectReachable(topLevel, parseResult.objects)
        val missingRefs = findMissingRefs(allObjects, parseResult.objects)

        val hkx = try {
            HkPackfileWriter(HkHeader(), allObjects, registry).write()
        } catch (e: Exception) {
            errors.add("Serialization failed: ${e.message}")
            return ConversionResult(null, errors, ValidationReport(
                reachableObjects = allObjects.size,
                missingRefs = missingRefs,
                unresolvedClasses = 0,
                placeholderValues = 0,
            ))
        }

        // The writer resolves nested anonymous object classes in place, so this
        // has to run after write().
        val unresolvedClasses = allObjects.count { registry.layout(it.className) == null }
        val placeholders = countPlaceholders(allObjects)

        return ConversionResult(
            hkx = hkx,
            errors = errors,
            validation = ValidationReport(
                reachableObjects = allObjects.size,
                missingRefs = missingRefs,
                unresolvedClasses = unresolvedClasses,
                placeholderValues = placeholders,
            ),
        )
    }

    private fun collectReachable(
        topLevel: HkObject,
        byName: Map<String, HkObject>,
    ): List<HkObject> {
        val result = mutableListOf(topLevel)
        val seen = mutableSetOf(topLevel)
        val queue = java.util.ArrayDeque<HkObject>()
        queue.add(topLevel)
        while (queue.isNotEmpty()) {
            val obj = queue.removeFirst()
            for (ref in referencedObjects(obj, byName)) {
                if (seen.add(ref)) {
                    queue.add(ref)
                    result.add(ref)
                }
            }
        }
        return result
    }

    private fun findMissingRefs(
        reachable: List<HkObject>,
        byName: Map<String, HkObject>,
    ): List<String> {
        val missing = linkedSetOf<String>()
        for (obj in reachable) {
            for (value in obj.params.values) {
                when (value) {
                    is HkValue.ObjectRef -> if (byName[value.refName] == null) missing.add(value.refName)
                    is HkValue.RefArray ->
                        value.refNames.forEach { if (byName[it] == null) missing.add(it) }
                    else -> {}
                }
            }
        }
        return missing.toList()
    }

    private fun countPlaceholders(reachable: List<HkObject>): Int {
        var count = 0
        for (obj in reachable) {
            if (obj.name.contains('$') || obj.className.contains('$')) count++
            for ((name, value) in obj.params) {
                if (name.contains('$')) {
                    count++
                    continue
                }
                val text = when (value) {
                    is HkValue.Primitive -> value.text
                    is HkValue.StringVal -> value.value
                    is HkValue.StringArray -> value.values.joinToString(" ")
                    else -> ""
                }
                if (text.contains('$')) count++
            }
        }
        return count
    }

    /**
     * Removes lines that are still unresolved FNIS template markers (<!-- $...$ -->).
     * The template engine should resolve these before conversion; any leftovers
     * would produce invalid XML.
     */
    private fun stripUnresolvedMarkers(xml: String): String {
        return xml.lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("<!-- $") && t.endsWith("-->")
        }.joinToString("\n")
    }

    private fun referencedObjects(obj: HkObject, byName: Map<String, HkObject>): List<HkObject> {
        val refs = mutableListOf<HkObject>()
        for (value in obj.params.values) {
            when (value) {
                is HkValue.ObjectRef -> byName[value.refName]?.let { refs.add(it) }
                is HkValue.RefArray -> value.refNames.forEach { n -> byName[n]?.let { refs.add(it) } }
                is HkValue.ObjectArray -> refs.addAll(value.objects)
                else -> {}
            }
        }
        return refs
    }
}
