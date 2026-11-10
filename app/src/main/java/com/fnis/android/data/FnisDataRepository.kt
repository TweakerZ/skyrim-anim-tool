package com.fnis.android.data

import com.fnis.android.model.AnimationGroup
import com.fnis.android.model.BehaviorTemplate
import com.fnis.android.model.FnisIni
import com.fnis.android.model.PatchDefinition
import java.io.File

/**
 * Loads the FNIS data files from a real filesystem path (the Skyrim Data
 * folder). Keeping the parsed data in memory lets the Patches tab and the
 * generator share one copy.
 */
object FnisDataRepository {

    data class LoadReport(
        val ok: Boolean,
        val lines: List<String>,
        val errors: List<String>
    )

    @Volatile var dataDir: File? = null
        private set
    @Volatile var ini: FnisIni = FnisIni()
        private set
    @Volatile var patches: List<PatchDefinition> = emptyList()
        private set
    @Volatile var groups: List<AnimationGroup> = emptyList()
        private set
    @Volatile var animVars: List<BehaviorVars> = emptyList()
        private set
    @Volatile var templates: List<BehaviorTemplate> = emptyList()
        private set
    @Volatile var templatesDir: File? = null
        private set
    @Volatile var loaded: Boolean = false
        private set

    fun load(dir: File): LoadReport {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()

        dataDir = resolveDataFolder(dir)
        val root = dataDir ?: dir

        val iniFile = listOf(resolveChild(root, "FNIS.ini"), resolveChild(root, "FNIS.ini0"))
            .firstOrNull { it.isFile }
        if (iniFile != null) {
            ini = IniParser.parse(iniFile.readText())
            lines += "FNIS ini: ${iniFile.name}"
        } else {
            ini = FnisIni()
            errors += "FNIS.ini not found in ${root.path}"
        }

        val tools = resolveChild(resolveChild(root, "tools"), "GenerateFNIS_for_Users")

        patches = readText(tools, "PatchList.txt", lines, errors)
            ?.let { PatchListParser.parse(it) } ?: emptyList()
        lines += "Patches loaded: ${patches.size}"

        groups = readText(tools, "Alternate_AnimationGroups.txt", lines, errors)
            ?.let { AnimationGroupsParser.parse(it) } ?: emptyList()
        lines += "Animation groups loaded: ${groups.size}"

        animVars = readText(tools, "AnimVars.txt", lines, errors)
            ?.let { AnimVarsParser.parse(it) } ?: emptyList()
        lines += "Behavior variable sets loaded: ${animVars.size}"

        templates = readText(tools, "FNIS_Templates.txt", lines, errors)
            ?.let { TemplateParser.parse(it) } ?: emptyList()
        lines += "Behavior templates loaded: ${templates.size}"

        templatesDir = resolveChild(tools, "templates")
        if (!templatesDir!!.isDirectory) {
            errors += "Template folder not found: ${templatesDir!!.path}"
        }

        diagnoseInstall(root, lines, errors)

        loaded = patches.isNotEmpty() || groups.isNotEmpty()
        return LoadReport(loaded, lines, errors)
    }

    /**
     * Checks the parts of the install that decide whether the game can animate
     * at all. The generator data can load fine while the game still T-poses,
     * so these findings are surfaced as warnings.
     */
    private fun diagnoseInstall(
        root: File,
        lines: MutableList<String>,
        errors: MutableList<String>
    ) {
        val esp = resolveChild(root, "FNIS.esp")
        if (esp.isFile) {
            lines += "FNIS.esp present"
        } else {
            errors += "FNIS.esp missing — install the FNIS mod zip and enable the plugin"
        }

        val scripts = resolveChild(root, "scripts")
        if (resolveChild(scripts, "FNIS_aa.pex").isFile) {
            lines += "FNIS_aa.pex present"
        } else {
            errors += "scripts/FNIS_aa.pex missing — the FNIS mod zip is not installed correctly"
        }
        val generatedScripts = listOf("FNIS_aa2.pex", "FNIS_PCEA2.pex", "FNISVersionGenerated.pex")
        val missingScripts = generatedScripts.filter { !resolveChild(scripts, it).isFile }
        if (missingScripts.isNotEmpty()) {
            errors += "Generator scripts missing: ${missingScripts.joinToString(", ")} — run FNIS generation"
        }

        val behaviors = resolveChild(
            resolveChild(resolveChild(resolveChild(root, "Meshes"), "actors"), "character"),
            "behaviors"
        )
        val master = resolveChild(behaviors, "0_master.hkx")
        if (!master.isFile) {
            errors += "0_master.hkx missing — characters will T-pose"
        } else {
            val first = try {
                master.inputStream().use { it.read() }
            } catch (_: Exception) {
                -1
            }
            if (first == '<'.code) {
                errors += "0_master.hkx is XML text, not binary Havok — characters will T-pose"
            } else {
                lines += "Behavior files look binary"
                val missing = missingFnisBehaviors(master, behaviors)
                if (missing.isEmpty()) {
                    lines += "FNIS behavior references resolve"
                } else {
                    missing.forEach { name ->
                        errors += "0_master.hkx references $name, which is missing — characters will T-pose"
                    }
                }
            }
        }

        val animationData = resolveChild(resolveChild(root, "Meshes"), "animationdatasinglefile.txt")
        if (animationData.isFile) {
            val text = try {
                animationData.readText()
            } catch (_: Exception) {
                ""
            }
            if (text.contains("\$")) {
                errors += "animationdatasinglefile.txt still contains template markers — regenerate it"
            } else {
                lines += "Animation data looks generated (${text.length} chars)"
            }
        }
    }

    /**
     * A behavior graph fails to load when a referenced behavior file is absent,
     * and the character then renders in bind pose. Vanilla behaviors live in the
     * BSA, so only FNIS-generated references are checked here.
     */
    private fun missingFnisBehaviors(master: File, behaviors: File): List<String> {
        val bytes = try {
            master.readBytes()
        } catch (_: Exception) {
            return emptyList()
        }
        val text = String(bytes, Charsets.ISO_8859_1)
        val refs = Regex("""FNIS[A-Za-z0-9_]*_Behavior\.hkx""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .distinct()
        return refs.filter { name -> !resolveChild(behaviors, name).isFile }.toList()
    }

    fun clear() {
        dataDir = null
        ini = FnisIni()
        patches = emptyList()
        groups = emptyList()
        animVars = emptyList()
        templates = emptyList()
        templatesDir = null
        loaded = false
    }

    /**
     * Users often pick the Skyrim root instead of Data. Walk into Data when
     * the selected folder has one.
     */
    private fun resolveDataFolder(picked: File): File {
        if (picked.name.equals("Data", ignoreCase = true)) return picked
        val nested = resolveChild(picked, "Data")
        return if (nested.isDirectory) nested else picked
    }

    private fun resolveChild(dir: File, name: String): File {
        val direct = File(dir, name)
        if (direct.exists()) return direct
        val match = dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return match ?: direct
    }

    private fun readText(
        dir: File,
        name: String,
        lines: MutableList<String>,
        errors: MutableList<String>
    ): String? {
        val file = resolveChild(dir, name)
        if (!file.isFile) {
            errors += "$name not found in ${dir.path}"
            return null
        }
        return try {
            val text = file.readText()
            lines += "$name: ${text.length} chars"
            text
        } catch (e: Exception) {
            errors += "$name could not be read: ${e.message}"
            null
        }
    }
}
