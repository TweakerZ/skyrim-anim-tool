package com.fnis.android.hkx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HkxConverterTest {

    private val testXml = """
        <?xml version="1.0" encoding="ascii"?>
        <hkpackfile classversion="8" contentsversion="hk_2010.2.0-r1" toplevelobject="#0027">

        	<hksection name="__data__">

        		<hkobject name="#0028" class="hkbMirroredSkeletonInfo" signature="0xc6c2da4f">
        			<hkparam name="mirrorAxis">(1.000000 0.000000 0.000000 0.000000)</hkparam>
        			<hkparam name="bonePairMap" numelements="64">
        				0 1 2 3 4 5 9 10 11 6 7 8 15 16 17 12
        				13 14 18 19 20 21 22 23 24 25 26 30 31 32 27 28
        				29 33 34 35 36 37 39 38 41 40 42 43 45 44 46 48
        				47 49 51 50 56 57 58 59 52 53 54 55 60 61 62 63
        				64 65 66 82 83 84 85 86 87 88 89 90 91 92 93 94
        				95 96 67 68 69 70 71 72 73 74 75 76 77 78 79 80
        				81 97 98
        			</hkparam>
        		</hkobject>

        		<hkobject name="#0027" class="hkRootLevelContainer" signature="0x2772c11e">
        			<hkparam name="namedVariants" numelements="1">
        				<hkobject>
        					<hkparam name="name">hkbBehaviorGraph</hkparam>
        					<hkparam name="className">hkbBehaviorGraph</hkparam>
        					<hkparam name="variant">#0029</hkparam>
        				</hkobject>
        			</hkparam>
        		</hkobject>

        		<hkobject name="#0029" class="hkbBehaviorGraph" signature="0xb1218f86">
        			<hkparam name="variableMode">VARIABLE_MODE_DISCARD_WHEN_INACTIVE</hkparam>
        			<hkparam name="rootGenerator">#0030</hkparam>
        			<hkparam name="data">#0031</hkparam>
        		</hkobject>

        		<hkobject name="#0030" class="hkbStateMachine" signature="0x816c1dcb">
        			<hkparam name="variableBindingSet">null</hkparam>
        			<hkparam name="userData">0</hkparam>
        			<hkparam name="name">RootStateMachine</hkparam>
        		</hkobject>

        		<hkobject name="#0031" class="hkbBehaviorGraphData" signature="0x95aca5d">
        			<hkparam name="attributeDefaults" numelements="0">
        			</hkparam>
        			<hkparam name="variableInfos" numelements="0">
        			</hkparam>
        			<hkparam name="characterPropertyInfos" numelements="0">
        			</hkparam>
        			<hkparam name="eventInfos" numelements="0">
        			</hkparam>
        			<hkparam name="wordMinVariableValues" numelements="0">
        			</hkparam>
        			<hkparam name="wordMaxVariableValues" numelements="0">
        			</hkparam>
        			<hkparam name="variableInitialValues">null</hkparam>
        			<hkparam name="stringData">#0032</hkparam>
        		</hkobject>

        		<hkobject name="#0032" class="hkbBehaviorGraphStringData" signature="0xc713064e">
        			<hkparam name="eventNames" numelements="2">
        				<hkcstring>FootLeft</hkcstring>
        				<hkcstring>FootRight</hkcstring>
        			</hkparam>
        			<hkparam name="attributeNames" numelements="0">
        			</hkparam>
        			<hkparam name="variableNames" numelements="0">
        			</hkparam>
        			<hkparam name="characterPropertyNames" numelements="0">
        			</hkparam>
        		</hkobject>

        	</hksection>

        </hkpackfile>
    """.trimIndent()

    @Test
    fun testParseSimpleXml() {
        val parser = HkXmlParser()
        val result = parser.parse(testXml)

        assertNotNull(result.topLevel)
        assertEquals("#0027", result.topLevel!!.name)
        assertEquals("hkRootLevelContainer", result.topLevel!!.className)
        assertTrue(result.objects.size >= 6)
    }

    @Test
    fun testConvertSimpleXml() {
        val converter = HkxConverter()
        val result = converter.convert(testXml)

        assertTrue("Errors: ${result.errors.joinToString(", ")}", result.errors.isEmpty())
        assertNotNull(result.hkx)

        val hkx = result.hkx!!
        // Check magic (little-endian: 57 e0 e0 57 = 0x57E0E057)
        val magic = (hkx[0].toInt() and 0xFF) or ((hkx[1].toInt() and 0xFF) shl 8) or ((hkx[2].toInt() and 0xFF) shl 16) or ((hkx[3].toInt() and 0xFF) shl 24)
        assertEquals(0x57E0E057, magic)
        // Check version string at offset 40
        val verStr = String(hkx.copyOfRange(40, 56)).trim()
        assertTrue("Version string: $verStr", verStr.startsWith("hk_2010"))
        // Check section headers
        fun sectionName(range: IntRange): String =
            String(hkx.copyOfRange(range.first, range.last + 1)).trim { it <= ' ' || it == '\u00FF' }
        assertEquals("__classnames__", sectionName(64..82))
        assertEquals("__types__", sectionName(112..130))
        assertEquals("__data__", sectionName(160..178))
    }

    @Test
    fun testClassRegistry() {
        val layout = HkClassRegistry.layout("hkbBehaviorGraph")!!
        assertEquals(0xb1218f86.toInt(), layout.signature)
        assertEquals(304, layout.size)

        val mirrorLayout = HkClassRegistry.layout("hkbMirroredSkeletonInfo")!!
        assertEquals(0xc6c2da4f.toInt(), mirrorLayout.signature)
    }

    @Test
    fun testValidationAcceptsCompleteGraph() {
        val result = HkxConverter().convert(testXml)

        val validation = result.validation
        assertNotNull(validation)
        assertTrue("Validation: ${validation!!.summary()}", validation.isValid)
        assertEquals(0, validation.unresolvedClasses)
    }

    @Test
    fun testValidationRejectsDanglingReference() {
        // #0030 is referenced by the behavior graph but never defined. Skyrim
        // discards such a graph and T-poses the character.
        val brokenXml = testXml.replace(
            "<hkobject name=\"#0030\"",
            "<hkobject name=\"#0098\""
        )

        val result = HkxConverter().convert(brokenXml)

        val validation = result.validation
        assertNotNull(validation)
        assertTrue("Expected invalid graph", !validation!!.isValid)
        assertTrue("#0030 should be reported", validation.missingRefs.contains("#0030"))
    }

    @Test
    fun testValidationRejectsUnresolvedTemplateValue() {
        val unresolved = testXml.replace("RootStateMachine", "\$:AAGroupVars+28\$")

        val validation = HkxConverter().convert(unresolved).validation

        assertNotNull(validation)
        assertTrue("Expected invalid graph", !validation!!.isValid)
        assertEquals(1, validation!!.placeholderValues)
    }
}
