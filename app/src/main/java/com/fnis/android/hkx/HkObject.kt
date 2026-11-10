package com.fnis.android.hkx

/**
 * A generic Havok packfile object. Instances are populated by parsing XML
 * templates and then serialized to binary packfile format by [HkPackfileWriter].
 */
data class HkObject(
    val name: String,          // e.g. "#0106" or "#anon_3"
    var className: String,     // e.g. "hkbBehaviorGraphStringData" or "" for anonymous
    val signature: Int,        // e.g. 0xc713064e, or 0 for anonymous
    val params: MutableMap<String, HkValue> = mutableMapOf(),
)

/**
 * A Havok member value.
 */
sealed class HkValue {
    data class Primitive(val text: String) : HkValue()
    data class StringVal(val value: String) : HkValue()
    data class ObjectRef(val refName: String) : HkValue()  // e.g. "#0105"
    data class StringArray(val values: List<String>) : HkValue()
    data class ObjectArray(val objects: List<HkObject>) : HkValue()
    data class RefArray(val refNames: List<String>) : HkValue()  // e.g. "#2520 #0334"
    data class ValArray(val values: List<HkValue>) : HkValue()
    object Null : HkValue()
}

/**
 * Parses an hkpackfile XML document (as produced by FNIS templates / hkxcmd
 * XML output) into a tree of [HkObject] nodes.
 *
 * XML patterns handled:
 *   <hkobject name="#0106" class="ClassName" signature="0x....">   named object
 *   <hkobject>                                                       anonymous inline struct
 *   <hkparam name="memberName">simple value</hkparam>               primitive value
 *   <hkparam name="memberName">#0105</hkparam>                      object reference
 *   <hkparam name="memberName" numelements="3">                     array
 *     <hkcstring>a</hkcstring>                                      → string array
 *     <hkobject>...</hkobject>                                       → object array
 *   </hkparam>
 */
class HkXmlParser {

    private var anonCounter = 0

    private val whitespaceRegex = Regex("\\s+")
    private val refRegex = Regex("#\\S+")

    fun parse(xml: String): HkParseResult {
        // Strip XML comments (FNIS directives are already resolved by the
        // template processor; leftover comments inside <hkparam> arrays would
        // otherwise be parsed as values).
        val withoutComments = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        // Split inline tags (e.g. "<hkparam ...></hkparam>" and
        // "<hkobject><hkparam>..</hkparam></hkobject>" on one line) so the
        // line-based parser sees one tag per line.
        val normalized = withoutComments.replace("><", ">\n<")
        val lines = normalized.lines()
        val objects = linkedMapOf<String, HkObject>()
        var topLevelName: String? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            val tl = extractAttribute(line, "toplevelobject")
            if (tl != null) topLevelName = tl

            // Named hkobject: <hkobject name="#0106" class="..." signature="0x...">
            val named = extractNamedHkObject(line)
            if (named != null) {
                val (name, className, signature) = named
                val obj = HkObject(name, className, signature)
                objects[name] = obj
                val body = readObjectBody(lines, i + 1, obj, objects)
                i = body.nextIndex
                continue
            }

            // Anonymous hkobject at top level (rare)
            if (line.trim().startsWith("<hkobject>") || line.trim().startsWith("<hkobject ")) {
                if (extractAttribute(line, "name") == null) {
                    val anonName = "#anon_${anonCounter++}"
                    val obj = HkObject(anonName, "", 0)
                    objects[anonName] = obj
                    val body = readObjectBody(lines, i + 1, obj, objects)
                    i = body.nextIndex
                    continue
                }
            }

            i++
        }

        val topLevel = topLevelName?.let { objects[it] }
        return HkParseResult(objects, topLevel)
    }

    private data class HkObjectBody(val obj: HkObject, val nextIndex: Int)

    /**
     * Reads the body of an hkobject (its params), starting at the line after the
     * opening <hkobject> tag. Stops at the matching </hkobject>.
     */
    private fun readObjectBody(
        lines: List<String>,
        start: Int,
        obj: HkObject,
        objects: MutableMap<String, HkObject>,
    ): HkObjectBody {
        var i = start
        while (i < lines.size) {
            val trimmed = lines[i].trim()

            if (trimmed == "</hkobject>") {
                return HkObjectBody(obj, i + 1)
            }

            val pm = extractHkParamOpening(lines[i])
            if (pm != null) {
                val (memberName, numElements) = pm
                // Single-line param: <hkparam ...>value</hkparam> (common in FNIS templates)
                val singleLineClose = lines[i].indexOf("</hkparam>")
                if (singleLineClose >= 0) {
                    val openEnd = lines[i].indexOf('>') + 1
                    val inline = lines[i].substring(openEnd, singleLineClose).trim().replace(whitespaceRegex, " ")
                    obj.params[memberName] = when {
                        inline.isEmpty() || inline == "null" -> HkValue.Null
                        inline.matches(refRegex) -> HkValue.ObjectRef(inline)
                        else -> HkValue.Primitive(inline)
                    }
                    i++
                    continue
                }
                val closingIdx = indexOfClosingTag(lines, i + 1, "</hkparam>")
                if (closingIdx < 0) {
                    i++
                    continue
                }
                val openEndIdx = lines[i].indexOf('>')
                val trailing = if (openEndIdx >= 0) lines[i].substring(openEndIdx + 1).trim() else ""
                val contentLines = if (trailing.isNotEmpty()) listOf(trailing) + lines.subList(i + 1, closingIdx) else lines.subList(i + 1, closingIdx)

                // Determine value type from first non-empty content line
                val first = contentLines.firstOrNull { it.trim().isNotEmpty() }?.trim()
                when {
                    first == null || first == "" -> {
                        obj.params[memberName] = HkValue.Null
                        i = closingIdx + 1
                    }
                    first.startsWith("<hkcstring>") -> {
                        // String array
                        val strings = contentLines
                            .map { it.trim() }
                            .filter { it.startsWith("<hkcstring>") }
                            .mapNotNull { extractHkcstring(it) }
                        obj.params[memberName] = HkValue.StringArray(strings)
                        i = closingIdx + 1
                    }
                    first.startsWith("<hkobject>") || first.startsWith("<hkobject ") -> {
                        // Object array: each child <hkobject> is an element
                        val objs = mutableListOf<HkObject>()
                        var j = i + 1
                        while (j < closingIdx) {
                            val ct = lines[j].trim()
                            if (ct.startsWith("<hkobject>") || ct.startsWith("<hkobject ")) {
                                val nested = parseAnonymousObject(lines, j, objects)
                                if (nested != null) {
                                    objs.add(nested.obj)
                                    objects[nested.obj.name] = nested.obj
                                    j = nested.nextIndex
                                } else {
                                    j++
                                }
                            } else {
                                j++
                            }
                        }
                        obj.params[memberName] = HkValue.ObjectArray(objs)
                        i = closingIdx + 1
                    }
                    else -> {
                        // Simple primitive value: read all content lines, join
                        val sb = StringBuilder()
                        for (cl in contentLines) {
                            val t = cl.trim()
                            if (t.isNotEmpty() && !t.startsWith("</hkparam>")) {
                                if (sb.isNotEmpty()) sb.append(" ")
                                sb.append(t)
                            }
                        }
                        val raw = sb.toString()
                        val joined = raw.replace(whitespaceRegex, " ").trim()
                        val tokens = joined.split(" ").filter { it.isNotEmpty() }
                        when {
                            tokens.isNotEmpty() && tokens.all { refRegex.matches(it) } -> {
                                // Object reference array (numelements may be a $expression$)
                                obj.params[memberName] = HkValue.RefArray(tokens)
                            }
                            joined.startsWith("(") && joined.endsWith(")") -> {
                                // Vector value: (x y z w)
                                val inner = joined.substring(1, joined.length - 1)
                                val values = inner.split(whitespaceRegex).mapNotNull { s ->
                                    s.toFloatOrNull()?.let { HkValue.Primitive(s.toString()) }
                                }
                                obj.params[memberName] = HkValue.ValArray(values)
                            }
                            tokens.size > 1 -> {
                                // Space-separated primitive array (int/float/bone indices)
                                obj.params[memberName] = HkValue.ValArray(tokens.map { HkValue.Primitive(it) })
                            }
                            joined == "null" -> {
                                obj.params[memberName] = HkValue.Null
                            }
                            refRegex.matches(joined) -> {
                                obj.params[memberName] = HkValue.ObjectRef(joined)
                            }
                            else -> {
                                obj.params[memberName] = HkValue.Primitive(joined)
                            }
                        }
                        i = closingIdx + 1
                    }
                }
                continue
            }

            // Anonymous object directly in body (not inside a param)
            if ((trimmed.startsWith("<hkobject>") || trimmed.startsWith("<hkobject ")) &&
                extractAttribute(lines[i], "name") == null) {
                val anonName = "#anon_${anonCounter++}"
                val anonObj = HkObject(anonName, "", 0)
                objects[anonName] = anonObj
                val body = readObjectBody(lines, i + 1, anonObj, objects)
                // Store as a param with a synthetic key? Anonymous objects in body
                // are unusual; skip for now.
                i = body.nextIndex
                continue
            }

            i++
        }
        return HkObjectBody(obj, i)
    }

    /**
     * Parses an anonymous <hkobject> element starting at line index [start].
     * Used for inline structs inside arrays.
     */
    private fun parseAnonymousObject(
        lines: List<String>,
        start: Int,
        objects: MutableMap<String, HkObject>,
    ): HkObjectBody? {
        val line = lines[start]
        val trimmed = line.trim()
        if (!trimmed.startsWith("<hkobject>") && !trimmed.startsWith("<hkobject ")) return null
        if (extractAttribute(line, "name") != null) return null // named object, not anonymous

        val anonName = "#anon_${anonCounter++}"
        val obj = HkObject(anonName, "", 0)
        objects[anonName] = obj

        val body = readObjectBody(lines, start + 1, obj, objects)
        return HkObjectBody(obj, body.nextIndex)
    }

    private fun indexOfClosingTag(lines: List<String>, from: Int, tag: String): Int {
        var depth = 1
        var j = from
        while (j < lines.size) {
            val t = lines[j].trim()
            if (t.startsWith(tag)) {
                depth--
                if (depth == 0) return j
            } else if (tag == "</hkparam>" && extractHkParamOpening(lines[j]) != null) {
                // Single-line <hkparam>..</hkparam> is self-closed; ignore for depth.
                if (!lines[j].contains("</hkparam>")) depth++
            } else if (tag == "</hkobject>" &&
                (t.startsWith("<hkobject>") || t.startsWith("<hkobject ")) &&
                !t.contains("</hkobject>")
            ) {
                depth++
            }
            j++
        }
        return -1
    }

    /**
     * Extracts (name, className, signature) from a named <hkobject ...> tag.
     * Returns null if not a named hkobject opening tag.
     */
    private fun extractNamedHkObject(line: String): Triple<String, String, Int>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("<hkobject")) return null
        if (trimmed.startsWith("<hkobject>")) return null // anonymous, no space after tag
        val name = extractAttribute(trimmed, "name") ?: return null
        val className = extractAttribute(trimmed, "class") ?: return null
        val sigStr = extractAttribute(trimmed, "signature") ?: "0x0"
        val signature = try {
            sigStr.toIntOrNull() ?: Integer.parseInt(sigStr.removePrefix("0x"), 16)
        } catch (_: Exception) { 0 }
        return Triple(name, className, signature)
    }

    /**
     * Extracts (memberName, numElements) from a <hkparam ...> opening tag.
     * Returns null if not an hkparam opening tag.
     */
    private fun extractHkParamOpening(line: String): Pair<String, Int>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("<hkparam")) return null
        if (trimmed.startsWith("<hkparam>")) return null // malformed
        val memberName = extractAttribute(trimmed, "name") ?: return null
        val numStr = extractAttribute(trimmed, "numelements")
        val numElements = numStr?.toIntOrNull() ?: 0
        return Pair(memberName, numElements)
    }

    private fun extractHkcstring(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("<hkcstring>")) return null
        val start = trimmed.indexOf('>') + 1
        val end = trimmed.indexOf('<', start)
        return if (end < 0) trimmed.substring(start) else trimmed.substring(start, end)
    }

    /**
     * Reads `attr="value"` from an XML tag. Scanned by hand because this runs
     * once per line of multi-megabyte templates, where compiling a regex per
     * call dominated generation time.
     */
    private fun extractAttribute(line: String, attr: String): String? {
        val needle = "$attr=\""
        var idx = line.indexOf(needle)
        while (idx >= 0) {
            val atBoundary = idx == 0 || line[idx - 1].isWhitespace()
            if (atBoundary) {
                val valueStart = idx + needle.length
                val valueEnd = line.indexOf('"', valueStart)
                return if (valueEnd < 0) null else line.substring(valueStart, valueEnd)
            }
            idx = line.indexOf(needle, idx + 1)
        }
        return null
    }
}

data class HkParseResult(
    val objects: Map<String, HkObject>,
    val topLevel: HkObject?,
)
