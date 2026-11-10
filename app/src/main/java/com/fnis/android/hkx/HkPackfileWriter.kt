package com.fnis.android.hkx

import com.fnis.android.hkx.HkClassRegistry.HkClassLayout
import com.fnis.android.hkx.HkClassRegistry.HkMember
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.LinkedHashMap

private const val ARRAY_FLAG = 0x80000000.toInt()

/**
 * Serializes a tree of [HkObject] nodes to a Havok binary packfile (.hkx),
 * Skyrim LE format (hk_2010.2.0-r1, PointerSize=4, little-endian).
 *
 * Ported from HKX2Library's PackFileSerializer.
 */
class HkPackfileWriter(
    private val header: HkHeader,
    private val rootObjects: List<HkObject>,
    private val registry: HkClassRegistry,
) {

    fun write(): ByteArray {
        // Phase 1: collect all reachable objects
        val allObjects = collectReachableObjects(rootObjects)

        // Phase 1b: anonymous objects inherit their class from the owning member
        resolveAnonymousClasses(allObjects)

        // Phase 2: build classnames section data
        val classNamesData = buildClassnamesData(allObjects)

        // Phase 3: serialize all objects to data section
        val serialization = serializeDataSection(allObjects)

        // Phase 4: write complete file
        return assembleFile(classNamesData, serialization)
    }

    private fun collectReachableObjects(roots: List<HkObject>): List<HkObject> {
        val result = mutableListOf<HkObject>()
        val seen = mutableSetOf<HkObject>()
        val queue = ArrayDeque<HkObject>()
        val byName = roots.associateBy { it.name }
        for (r in roots) {
            if (seen.add(r)) {
                queue.add(r)
                result.add(r)
            }
        }
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

    private fun referencedObjects(obj: HkObject, byName: Map<String, HkObject>): List<HkObject> {
        val refs = mutableListOf<HkObject>()
        for (value in obj.params.values) {
            when (value) {
                is HkValue.ObjectArray -> refs.addAll(value.objects)
                is HkValue.ObjectRef -> byName[value.refName]?.let { refs.add(it) }
                is HkValue.RefArray -> value.refNames.forEach { n -> byName[n]?.let { refs.add(it) } }
                else -> {}
            }
        }
        return refs
    }

    /**
     * Nested FNIS XML objects carry no class attribute; the class is implied by
     * the member schema of the parent (e.g. hkbStateMachine.states holds
     * hkbStateMachineStateInfo). Walk the graph once and assign those classes so
     * the classnames section and the serializer can lay the objects out.
     */
    private fun resolveAnonymousClasses(objects: List<HkObject>) {
        val byName = objects.associateBy { it.name }
        val visited = mutableSetOf<HkObject>()

        fun visit(obj: HkObject) {
            if (!visited.add(obj)) return
            val layout = registry.layout(obj.className) ?: return
            for (baseLayout in buildBaseChain(layout)) {
                for (member in baseLayout.members) {
                    when (val value = obj.params[member.name]) {
                        is HkValue.ObjectArray -> {
                            for (child in value.objects) {
                                if (child.className.isEmpty() && !member.memberClass.isNullOrEmpty()) {
                                    child.className = member.memberClass ?: ""
                                }
                                visit(child)
                            }
                        }
                        is HkValue.RefArray -> for (n in value.refNames) byName[n]?.let { visit(it) }
                        is HkValue.ObjectRef -> byName[value.refName]?.let { visit(it) }
                        else -> {}
                    }
                }
            }
        }

        objects.forEach { visit(it) }
    }

    private val classNameOffsets = LinkedHashMap<String, Int>()

    private fun buildClassnamesData(allObjects: List<HkObject>): ByteArray {
        val out = ByteArrayOutputStream()
        val bw = BinaryWriter(out, littleEndian = true, pointerSize = 4)

        val standards = listOf(
            "hkClass" to 0x75585EF6.toInt(),
            "hkClassMember" to 0x5C7EA4C2.toInt(),
            "hkClassEnum" to 0x8A3609CF.toInt(),
            "hkClassEnumItem" to 0xCE6F8A6C.toInt(),
        )
        for ((className, sig) in standards) {
            writeClassNameEntry(bw, className, sig)
        }

        val seen = mutableSetOf<String>()
        for (obj in allObjects) {
            if (!seen.add(obj.className)) continue
            val layout = registry.layout(obj.className)
            val sig = layout?.signature ?: obj.signature
            writeClassNameEntry(bw, obj.className, sig)
        }

        bw.writeUint32(0xFFFFFFFF.toInt())
        return out.toByteArray()
    }

    private fun writeClassNameEntry(bw: BinaryWriter, className: String, signature: Int) {
        val offset = bw.position
        bw.writeUint32(signature)
        bw.writeByte(0x09)
        bw.writeAscii(className, nullTerminated = true)
        classNameOffsets[className] = offset + 5
    }

    private fun serializeDataSection(allObjects: List<HkObject>): SerializationResult {
        val dataOut = ByteArrayOutputStream()
        val bw = BinaryWriter(dataOut, littleEndian = true, pointerSize = 4)

        val serialized = mutableSetOf<HkObject>()
        val queue = ArrayDeque<HkObject>()
        val localQueue = ArrayDeque<LocalWriteAction>()
        val pendingGlobals = mutableMapOf<HkObject, MutableList<Int>>()
        val globalLookup = LinkedHashMap<HkObject, Int>()
        val byName = allObjects.associateBy { it.name }

        val localFixups = LinkedHashMap<Int, Int>()
        val globalFixups = mutableListOf<GlobalFixupRec>()
        val virtualFixups = mutableListOf<VirtualFixupRec>()

        for (obj in allObjects) {
            if (!serialized.contains(obj)) {
                serialized.add(obj)
                queue.add(obj)
            }
        }

        while (queue.isNotEmpty()) {
            val obj = queue.removeFirst()
            val objPos = bw.position
            objectOffsets[obj] = objPos

            val classNameOff = classNameOffsets[obj.className] ?: 0
            virtualFixups.add(VirtualFixupRec(objPos, classNameOff))

            pendingGlobals.remove(obj)?.forEach { srcPos ->
                globalFixups.add(GlobalFixupRec(srcPos, objPos))
            }
            globalLookup[obj] = objPos

            serializeObjectMembers(
                obj, bw, serialized, queue, localQueue, pendingGlobals, globalLookup,
                byName, localFixups, globalFixups,
            )

            while (localQueue.isNotEmpty()) {
                val action = localQueue.removeFirst()
                bw.pad(16)
                localFixups[action.srcPos] = bw.position
                action.write(bw)
                bw.pad(16)
            }
            bw.pad(16)
        }

        return SerializationResult(
            data = dataOut.toByteArray(),
            localFixups = localFixups.toMap(),
            globalFixups = globalFixups.toList(),
            virtualFixups = virtualFixups.toList(),
        )
    }

    private val objectOffsets = LinkedHashMap<HkObject, Int>()

    private fun serializeObjectMembers(
        obj: HkObject,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        val layout = registry.layout(obj.className) ?: return
        val baseChain = buildBaseChain(layout)

        for (baseLayout in baseChain) {
            for (member in baseLayout.members) {
                writeMember(
                    obj, member, bw, serialized, queue, localQueue,
                    pendingGlobals, globalLookup, byName, localFixups, globalFixups,
                )
            }
        }
    }

    private fun buildBaseChain(layout: HkClassLayout): List<HkClassLayout> {
        val chain = mutableListOf<HkClassLayout>()
        var current: HkClassLayout? = layout
        while (current != null) {
            chain.add(0, current)
            current = current.base?.let { registry.layout(it) }
        }
        return chain
    }

    private fun writeMember(
        obj: HkObject,
        member: HkMember,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        val value = obj.params[member.name]

        if (member.flags.contains("SERIALIZE_IGNORED")) {
            writeIgnoredMember(member, bw)
            return
        }

        when (member.havokType) {
            "TYPE_ARRAY" -> writeArrayMember(
                member, value, bw, serialized, queue, localQueue,
                pendingGlobals, globalLookup, byName, localFixups, globalFixups,
            )
            "TYPE_POINTER", "TYPE_STRINGPTR" -> writePointerMember(
                member, value, bw, serialized, queue, localQueue,
                pendingGlobals, globalLookup, byName, localFixups, globalFixups,
            )
            "TYPE_STRUCT" -> writeStructMember(
                member, value, bw, serialized, queue, localQueue,
                pendingGlobals, globalLookup, byName, localFixups, globalFixups,
            )
            "TYPE_VECTOR4", "TYPE_QUATERNION" -> writeVectorMember(value, bw)
            else -> writeScalarMember(member, value, bw)
        }
    }

    private fun writePointerMember(
        member: HkMember,
        value: HkValue?,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        if (member.havokType == "TYPE_STRINGPTR") {
            writeStringPointer(value, bw, localQueue, localFixups)
            return
        }

        val target = when (value) {
            is HkValue.ObjectRef -> byName[value.refName]
            is HkValue.ObjectArray -> value.objects.firstOrNull()
            is HkValue.RefArray -> value.refNames.firstOrNull()?.let { byName[it] }
            else -> null
        }
        val srcPos = bw.position
        bw.writeUint32(0)
        if (target == null) return

        if (target.className.isEmpty() && !member.memberClass.isNullOrEmpty()) {
            target.className = member.memberClass ?: ""
        }
        val goff = globalLookup[target]
        if (goff != null) {
            globalFixups.add(GlobalFixupRec(srcPos, goff))
        } else {
            if (!serialized.contains(target)) {
                serialized.add(target)
                queue.add(target)
            }
            pendingGlobals.getOrPut(target) { mutableListOf() }.add(srcPos)
        }
    }

    private fun writeStringPointer(
        value: HkValue?,
        bw: BinaryWriter,
        localQueue: ArrayDeque<LocalWriteAction>,
        localFixups: LinkedHashMap<Int, Int>,
    ) {
        val text = when (value) {
            is HkValue.Primitive -> value.text
            is HkValue.StringVal -> value.value
            else -> null
        }
        val srcPos = bw.position
        bw.writeUint32(0)
        if (text.isNullOrEmpty() || text == "null") return
        localFixups[srcPos] = -1
        localQueue.add(LocalWriteAction(srcPos) { bw2 ->
            bw2.writeAscii(text, nullTerminated = true)
        })
    }

    private fun writeArrayMember(
        member: HkMember,
        value: HkValue?,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        val elements: List<Any> = when (value) {
            is HkValue.RefArray -> value.refNames
            is HkValue.ObjectArray -> value.objects
            is HkValue.ValArray -> value.values
            is HkValue.StringArray -> value.values
            is HkValue.Primitive -> listOf(value.text)
            else -> emptyList()
        }

        val srcPos = bw.position
        bw.writeUint32(0) // array data pointer placeholder
        bw.writeInt32(elements.size)
        bw.writeInt32(elements.size or ARRAY_FLAG)
        localFixups[srcPos] = -1
        if (elements.isEmpty()) return

        localQueue.add(LocalWriteAction(srcPos) { bw2 ->
            when {
                member.subType == "TYPE_STRUCT" -> {
                    for (e in elements) {
                        val child = e as? HkObject
                        if (child == null) {
                            bw2.writeZeros(registry.layout(member.memberClass ?: "")?.size ?: 4)
                        } else {
                            writeStructInline(
                                child, member.memberClass ?: "", bw2, serialized, queue, localQueue,
                                pendingGlobals, globalLookup, byName, localFixups, globalFixups,
                            )
                        }
                    }
                }
                member.subType == "TYPE_POINTER" -> {
                    for (e in elements) {
                        val target = when (e) {
                            is HkObject -> e
                            is String -> byName[e]
                            else -> null
                        }
                        val ptrPos = bw2.position
                        bw2.writeUint32(0)
                        if (target == null) continue
                        if (target.className.isEmpty() && !member.memberClass.isNullOrEmpty()) {
                            target.className = member.memberClass ?: ""
                        }
                        val goff = globalLookup[target]
                        if (goff != null) {
                            globalFixups.add(GlobalFixupRec(ptrPos, goff))
                        } else {
                            if (!serialized.contains(target)) {
                                serialized.add(target)
                                queue.add(target)
                            }
                            pendingGlobals.getOrPut(target) { mutableListOf() }.add(ptrPos)
                        }
                    }
                }
                member.subType == "TYPE_STRINGPTR" -> {
                    for (e in elements) {
                        val text = when (e) {
                            is String -> e
                            is HkValue.Primitive -> e.text
                            else -> null
                        }
                        val strPos = bw2.position
                        bw2.writeUint32(0)
                        if (text.isNullOrEmpty() || text == "null") continue
                        localFixups[strPos] = -1
                        localQueue.add(LocalWriteAction(strPos) { bw3 ->
                            bw3.writeAscii(text, nullTerminated = true)
                        })
                    }
                }
                else -> {
                    for (e in elements) {
                        val text = when (e) {
                            is HkValue.Primitive -> e.text
                            is String -> e
                            else -> "0"
                        }
                        writeArrayElement(member, text, bw2)
                    }
                }
            }
        })
    }

    private fun writeStructMember(
        member: HkMember,
        value: HkValue?,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        val child = (value as? HkValue.ObjectArray)?.objects?.firstOrNull()
        if (child == null) {
            bw.writeZeros(registry.layout(member.memberClass ?: "")?.size ?: 4)
            return
        }
        writeStructInline(
            child, member.memberClass ?: "", bw, serialized, queue, localQueue,
            pendingGlobals, globalLookup, byName, localFixups, globalFixups,
        )
    }

    private fun writeStructInline(
        obj: HkObject,
        className: String,
        bw: BinaryWriter,
        serialized: MutableSet<HkObject>,
        queue: ArrayDeque<HkObject>,
        localQueue: ArrayDeque<LocalWriteAction>,
        pendingGlobals: MutableMap<HkObject, MutableList<Int>>,
        globalLookup: Map<HkObject, Int>,
        byName: Map<String, HkObject>,
        localFixups: LinkedHashMap<Int, Int>,
        globalFixups: MutableList<GlobalFixupRec>,
    ) {
        val effective = if (className.isNotEmpty()) className else obj.className
        val layout = registry.layout(effective)
        if (layout == null) {
            bw.writeZeros(4)
            return
        }
        val start = bw.position
        for (baseLayout in buildBaseChain(layout)) {
            for (member in baseLayout.members) {
                writeMember(
                    obj, member, bw, serialized, queue, localQueue,
                    pendingGlobals, globalLookup, byName, localFixups, globalFixups,
                )
            }
        }
        val written = bw.position - start
        if (written < layout.size) bw.writeZeros(layout.size - written)
    }

    private fun writeVectorMember(value: HkValue?, bw: BinaryWriter) {
        val values = (value as? HkValue.ValArray)?.values
        if (values == null || values.size < 4) {
            bw.writeZeros(16)
            return
        }
        for (v in values.take(4)) {
            val t = (v as? HkValue.Primitive)?.text?.trim() ?: "0"
            bw.writeFloat32(t.toFloatOrNull() ?: 0f)
        }
    }

    private fun writeScalarMember(member: HkMember, value: HkValue?, bw: BinaryWriter) {
        when (value) {
            is HkValue.Primitive -> writePrimitive(member, value.text, bw)
            is HkValue.ValArray -> {
                val first = value.values.firstOrNull()
                if (first is HkValue.Primitive) writePrimitive(member, first.text, bw)
                else writeDefaultMember(member, bw)
            }
            else -> writeDefaultMember(member, bw)
        }
    }

    private fun writeIgnoredMember(member: HkMember, bw: BinaryWriter) {
        when (member.havokType) {
            "TYPE_ARRAY" -> {
                bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(ARRAY_FLAG)
            }
            "TYPE_STRUCT" -> bw.writeZeros(registry.layout(member.memberClass ?: "")?.size ?: 4)
            "TYPE_VECTOR4", "TYPE_QUATERNION" -> bw.writeZeros(16)
            else -> writeDefaultMember(member, bw)
        }
    }

    private fun writeDefaultMember(member: HkMember, bw: BinaryWriter) {
        when (member.havokType) {
            "TYPE_POINTER", "TYPE_STRINGPTR" -> bw.writeUint32(0)
            "TYPE_ARRAY" -> {
                bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(ARRAY_FLAG)
            }
            "TYPE_STRUCT" -> bw.writeZeros(registry.layout(member.memberClass ?: "")?.size ?: 4)
            "TYPE_VECTOR4", "TYPE_QUATERNION" -> bw.writeZeros(16)
            "TYPE_INT8", "TYPE_UINT8", "TYPE_BOOL", "TYPE_ENUM" -> bw.writeByte(0)
            "TYPE_INT16", "TYPE_UINT16", "TYPE_FLAGS" -> bw.writeInt16(0)
            "TYPE_INT32", "TYPE_UINT32" -> bw.writeInt32(0)
            "TYPE_REAL" -> bw.writeFloat32(0f)
            else -> bw.writeUint32(0)
        }
    }

    private fun writeNullMember(member: HkMember, bw: BinaryWriter) {
        writeDefaultMember(member, bw)
    }

    private fun writeArrayElement(member: HkMember, text: String, bw: BinaryWriter) {
        val t = text.trim()
        when (member.subType) {
            "TYPE_INT8", "TYPE_UINT8" -> bw.writeByte(t.toIntOrNull() ?: 0)
            "TYPE_INT16", "TYPE_UINT16" -> bw.writeInt16(t.toIntOrNull() ?: 0)
            "TYPE_REAL" -> bw.writeFloat32(t.toFloatOrNull() ?: 0f)
            "TYPE_INT32", "TYPE_UINT32" -> bw.writeInt32(t.toIntOrNull() ?: 0)
            else -> bw.writeInt32(t.toIntOrNull() ?: 0)
        }
    }

    private fun writePrimitive(member: HkMember, text: String, bw: BinaryWriter) {
        val t = text.trim()
        when (member.havokType) {
            "TYPE_BOOL" -> bw.writeByte(if (t.equals("true", ignoreCase = true) || t == "1") 1 else 0)
            "TYPE_INT8", "TYPE_UINT8" -> bw.writeByte(t.toIntOrNull() ?: 0)
            "TYPE_ENUM" -> {
                if (member.subType == "TYPE_INT8") bw.writeByte(enumValue(t)) else bw.writeInt32(enumValue(t))
            }
            "TYPE_INT16", "TYPE_UINT16" -> bw.writeInt16(t.toIntOrNull() ?: 0)
            "TYPE_FLAGS" -> {
                if (member.subType == "TYPE_UINT16") bw.writeUint16(flagsValue(t)) else bw.writeInt32(flagsValue(t))
            }
            "TYPE_INT32", "TYPE_UINT32" -> bw.writeInt32(parseInt(t))
            "TYPE_REAL" -> bw.writeFloat32(t.toFloatOrNull() ?: 0f)
            else -> when {
                t.startsWith("0x", ignoreCase = true) -> bw.writeUint32(t.substring(2).toIntOrNull(16) ?: 0)
                t.contains(".") || t.contains("e") || t.contains("E") -> bw.writeFloat32(t.toFloatOrNull() ?: 0f)
                else -> bw.writeInt32(parseInt(t))
            }
        }
    }

    private fun parseInt(t: String): Int =
        t.toIntOrNull() ?: t.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0

    private fun writePrimitiveValue(value: HkValue, bw: BinaryWriter) {
        if (value is HkValue.Primitive) {
            val t = value.text.trim()
            when {
                t.startsWith("0x", ignoreCase = true) -> bw.writeUint32(t.substring(2).toIntOrNull(16) ?: 0)
                t.contains(".") || t.contains("e") || t.contains("E") -> bw.writeFloat32(t.toFloatOrNull() ?: 0f)
                else -> bw.writeInt32(t.toIntOrNull() ?: 0)
            }
        }
    }

    /** Maps FNIS enum strings to integer values. */
    private fun enumValue(s: String): Int {
        val v = s.toIntOrNull()
        if (v != null) return v
        return when (s.lowercase()) {
            "variable_mode_discard_when_inactive" -> 1
            "variable_mode_keep_when_active" -> 0
            "variable_type_int32" -> 0
            "variable_type_uint32" -> 1
            "variable_type_real" -> 2
            "variable_type_bool" -> 3
            "role_default" -> 0
            else -> 0
        }
    }

    private fun flagsValue(s: String): Int {
        val v = s.toIntOrNull()
        if (v != null) return v
        return when (s.lowercase()) {
            "flag_none" -> 0
            else -> 0
        }
    }

    private fun assembleFile(
        classNamesData: ByteArray,
        serialization: SerializationResult,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val bw = BinaryWriter(out, littleEndian = true, pointerSize = 4)

        writeFileHeader(bw)

        val HEADER_SIZE = 64
        val SECTION_HEADER_SIZE = 48

        val sec0DataPos = HEADER_SIZE + 3 * SECTION_HEADER_SIZE // 208
        val sec0DataEnd = sec0DataPos + classNamesData.size
        val sec1DataPos = sec0DataEnd
        val sec2DataPos = alignTo(sec1DataPos, 16)

        // Section 0 header: __classnames__
        bw.writeFixStr("__classnames__", 19, 0)
        bw.writeByte(0xFF)
        bw.writeUint32(sec0DataPos)
        bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(0)
        bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(0)

        // Section 1 header: __types__ (empty)
        bw.writeFixStr("__types__", 19, 0)
        bw.writeByte(0xFF)
        bw.writeUint32(sec1DataPos)
        bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(0)
        bw.writeUint32(0); bw.writeUint32(0); bw.writeUint32(0)

        // Section 2 header: __data__
        bw.writeFixStr("__data__", 19, 0)
        bw.writeByte(0xFF)
        bw.writeUint32(sec2DataPos)

        val lfc = serialization.localFixups.size
        val gfc = serialization.globalFixups.size
        val vfc = serialization.virtualFixups.size
        val localStart = 0
        val globalStart = lfc * 8
        val virtStart = globalStart + gfc * 12
        val endPos = virtStart + vfc * 12

        bw.writeUint32(localStart)
        bw.writeUint32(globalStart)
        bw.writeUint32(virtStart)
        bw.writeUint32(endPos)
        bw.writeUint32(endPos)
        bw.writeUint32(endPos)

        // Section 0 data
        bw.writeBytes(classNamesData)
        bw.pad(16)

        // Section 1 data: empty

        // Section 2 data: object data
        bw.writeBytes(serialization.data)

        // Local fixups
        for ((src, dst) in serialization.localFixups.entries.sortedBy { it.key }) {
            bw.writeUint32(src + sec2DataPos)
            bw.writeUint32(dst + sec2DataPos)
        }
        bw.pad(16)

        // Global fixups
        for (gfu in serialization.globalFixups.sortedBy { it.src }) {
            bw.writeUint32(gfu.src + sec2DataPos)
            bw.writeUint32(2)
            bw.writeUint32(gfu.dst + sec2DataPos)
        }
        bw.pad(16)

        // Virtual fixups
        for (vfu in serialization.virtualFixups.sortedBy { it.src }) {
            bw.writeUint32(vfu.src + sec2DataPos)
            bw.writeUint32(0)
            bw.writeUint32(vfu.dst)
        }
        bw.pad(16)

        return out.toByteArray()
    }

    private fun alignTo(v: Int, alignment: Int): Int = (v + alignment - 1) / alignment * alignment

    private fun writeFileHeader(bw: BinaryWriter) {
        bw.writeUint32(0x57E0E057.toInt())
        bw.writeUint32(0x10C0C010.toInt())
        bw.writeInt32(0)
        bw.writeInt32(8)
        bw.writeByte(4)
        bw.writeByte(1)
        bw.writeByte(0)
        bw.writeByte(1)
        bw.writeInt32(3)
        bw.writeInt32(2)
        bw.writeInt32(0)
        bw.writeInt32(0)
        bw.writeInt32(75)
        bw.writeFixStr(header.contentsVersion, 16, 0xFF.toInt())
        bw.writeInt32(0)
        bw.writeInt16(-1)
        bw.writeInt16(-1)
    }

    private data class GlobalFixupRec(val src: Int, val dst: Int)
    private data class VirtualFixupRec(val src: Int, val dst: Int)

    private data class LocalWriteAction(
        val srcPos: Int,
        val write: (BinaryWriter) -> Unit,
    )

    private data class SerializationResult(
        val data: ByteArray,
        val localFixups: Map<Int, Int>,
        val globalFixups: List<GlobalFixupRec>,
        val virtualFixups: List<VirtualFixupRec>,
    )
}

data class HkHeader(val contentsVersion: String = "hk_2010.2.0-r1")

class BinaryWriter(
    private val out: ByteArrayOutputStream,
    private val littleEndian: Boolean = true,
    private val pointerSize: Int = 4,
) {

    private val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    var position: Int = 0
        get() = out.size()

    fun writeByte(v: Int) { out.write(v) }
    fun writeBoolean(v: Boolean) { out.write(if (v) 1 else 0) }
    fun writeInt8(v: Int) { out.write(v) }
    fun writeUint8(v: Int) { out.write(v) }
    fun writeInt16(v: Int) {
        buf.clear(); buf.putShort(v.toShort()); out.write(buf.array(), 0, 2)
    }
    fun writeUint16(v: Int) { writeInt16(v) }
    fun writeInt32(v: Int) {
        buf.clear(); buf.putInt(v); out.write(buf.array(), 0, 4)
    }
    fun writeUint32(v: Int) {
        buf.clear(); buf.putInt(v); out.write(buf.array(), 0, 4)
    }
    fun writeFloat32(v: Float) {
        buf.clear(); buf.putFloat(v); out.write(buf.array(), 0, 4)
    }
    fun writeInt64(v: Long) {
        buf.clear(); buf.putLong(v); out.write(buf.array(), 0, 8)
    }
    fun writeUint64(v: Long) { writeInt64(v) }

    fun writeAscii(s: String, nullTerminated: Boolean = true) {
        out.write(s.toByteArray(Charsets.UTF_8))
        if (nullTerminated) out.write(0)
    }

    fun writeFixStr(s: String, width: Int, padByte: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val len = bytes.size.coerceAtMost(width)
        out.write(bytes, 0, len)
        for (i in len until width) out.write(padByte)
    }

    fun writeBytes(ba: ByteArray) {
        out.write(ba)
    }

    fun writeZeros(count: Int) {
        for (i in 0 until count) out.write(0)
    }

    fun pad(alignment: Int) {
        while (out.size() % alignment != 0) out.write(0xFF)
    }
}
