package com.fnis.android.service

import android.content.Context
import com.fnis.android.R
import com.fnis.android.data.FnisDataRepository
import com.fnis.android.hkx.HkxConverter
import com.fnis.android.model.AnimationGroup
import com.fnis.android.model.AnimationSet
import com.fnis.android.model.FnisIni
import com.fnis.android.model.PatchDefinition
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FnisGeneratorService(private val context: Context) {

    private val outputDir = File(context.filesDir, "output")
    private val zipDir = File(context.cacheDir, "mod-package")

    fun initialize(): Boolean {
        outputDir.mkdirs()
        // Behavior artifacts from a previous run were produced by an older
        // generator and may not pass today's validation, so never let them be
        // packed into a mod archive without a fresh Generate.
        outputDir.listFiles { file -> file.isFile && file.name.endsWith(".hkx", ignoreCase = true) }
            ?.forEach { it.delete() }
        zipDir.deleteRecursively()
        return true
    }

    fun generateBehaviorFiles(
        ini: FnisIni,
        groups: List<AnimationGroup>,
        sets: List<AnimationSet>,
        patches: List<PatchDefinition>,
        templateProcessor: TemplateProcessor,
        disabledPatchIds: Set<String> = emptySet(),
        onProgress: (String) -> Unit = {}
    ): List<String> {
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val generated = mutableListOf<String>()
        val templatesDir = FnisDataRepository.templatesDir
        val enabledIds = buildSet {
            addAll(patches.map { it.patchId })
            addAll(listOf("FNIS", "FNIS_AA", "_NPC_SINGLE", "_DAHF", "_DDPASF"))
            if (ini.pscd) add("PSCD")
        }

        val hkxConverter = HkxConverter()

        if (templatesDir != null && templatesDir.isDirectory) {
            templatesDir.listFiles { file -> file.isFile && file.name.endsWith("_TEMPLATE.txt", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.forEach { template ->
                    val baseName = stripSuffix(template.name, "_TEMPLATE.txt")
                    onProgress(context.getString(R.string.status_generating_file, baseName))
                    val text = template.readText()
                    val processed = if (baseName.equals("animationdatasinglefile", ignoreCase = true) ||
                        baseName.equals("animationsetdatasinglefile", ignoreCase = true)
                    ) {
                        templateProcessor.processAnimationDataTemplate(text, enabledIds)
                    } else {
                        templateProcessor.processTemplate(
                            text,
                            baseName,
                            patches,
                            groups,
                            enabledIds
                        )
                    }
                    val outName = outputNameFor(template.name)
                    if (outName.endsWith(".hkx", ignoreCase = true)) {
                        // Convert XML template to binary Havok packfile
                        val conversion = hkxConverter.convert(processed)
                        val validation = conversion.validation
                        when {
                            conversion.hkx == null -> {
                                // Never write XML-as-hkx: the game cannot load it and T-poses.
                                generated += "$outName (CONVERSION FAILED: ${conversion.errors.joinToString(", ")})"
                            }
                            validation != null && !validation.isValid -> {
                                // A dangling reference makes Skyrim discard the entire
                                // behavior graph, which T-poses the character. Keep the
                                // working file on disk instead of shipping a broken one.
                                generated += "$outName (SKIPPED, behavior incomplete: ${validation.summary()})"
                            }
                            else -> {
                                File(outputDir, outName).writeBytes(conversion.hkx)
                                generated += outName
                            }
                        }
                    } else {
                        writeTextOutput(outName, processed)
                        generated += outName
                    }
                }
        }

        val pexTemplates = templatesDir?.listFiles { file ->
            file.isFile && file.name.endsWith("_TEMPLATE.pex", ignoreCase = true)
        }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()

        pexTemplates.forEach { pex ->
            val outName = stripSuffix(pex.name, "_TEMPLATE.pex") + ".pex"
            val bytes = if (outName == "FNISVersionGenerated.pex") {
                templateProcessor.patchVersionPex(pex.readBytes())
            } else {
                pex.readBytes()
            }
            File(outputDir, outName).writeBytes(bytes)
            generated += outName
        }

        writeTextOutput(
            "FNIS_Patches.txt",
            buildPatchManifest(ini, groups, patches, disabledPatchIds, templateProcessor)
        )
        generated += "FNIS_Patches.txt"

        return generated
    }

    /**
     * Builds the mod archive from the last generation.
     *
     * The archive holds the verified generated files at Data-relative paths, so
     * a mod loader can install it like any other mod. Behavior .hkx and
     * animation data files are excluded: a behavior that passes the structural
     * gate can still differ from the one the engine expects, and a wrong graph
     * crashes Skyrim on the first frame.
     */
    fun buildModZip(): ModPackage.Result {
        outputDir.mkdirs()
        val zip = File(zipDir, "Skyrim Anims.zip")
        if (zip.isFile) zip.delete()
        return ModPackage.build(outputDir, zip)
    }

    /**
     * Archive name proposed in the save dialog. The version makes it obvious
     * which build a mod manager has installed.
     */
    fun suggestedZipName(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        return if (version.isNullOrBlank()) {
            "Skyrim Anims.zip"
        } else {
            "Skyrim Anims $version.zip"
        }
    }

    fun writeOutput(fileName: String, content: String): File {
        outputDir.mkdirs()
        val outFile = File(outputDir, fileName)
        outFile.writeText(content)
        return outFile
    }

    private fun writeTextOutput(fileName: String, content: String): File {
        val crlf = content.replace("\r\n", "\n").replace("\n", "\r\n")
        return writeOutput(fileName, crlf)
    }

    fun getOutputDir(): File = outputDir

    private fun outputNameFor(templateName: String): String {
        val base = stripSuffix(templateName, "_TEMPLATE.txt")
        return when (base.lowercase(Locale.ROOT)) {
            "animationdatasinglefile" -> "animationdatasinglefile.txt"
            "animationsetdatasinglefile" -> "animationsetdatasinglefile.txt"
            else -> "$base.hkx"
        }
    }

    private fun stripSuffix(name: String, suffix: String): String =
        if (name.endsWith(suffix, ignoreCase = true)) {
            name.dropLast(suffix.length)
        } else {
            name
        }

    private fun buildPatchManifest(
        ini: FnisIni,
        groups: List<AnimationGroup>,
        patches: List<PatchDefinition>,
        disabledPatchIds: Set<String>,
        processor: TemplateProcessor
    ): String {
        val sb = StringBuilder()
        sb.appendLine("FNIS patch report")
        sb.appendLine("Generated: ${timestamp()}")
        sb.appendLine("Data folder: ${FnisDataRepository.dataDir?.absolutePath ?: "(none)"}")
        sb.appendLine(
            "Options: PSCD=${if (ini.pscd) 1 else 0}  " +
                "InstantExecute=${if (ini.instantExecute) 1 else 0}  " +
                "LoadCTDCalculation=${if (ini.loadCtdCalculation) 1 else 0}"
        )
        sb.appendLine("AA groups: ${groups.size}")
        sb.appendLine()
        sb.appendLine("Applied patches (${patches.size}):")
        patches.forEach { patch ->
            val targets = processor.behaviorNamesForMask(patch.requiredBehaviorPattern)
            sb.append("  ").append(patch.patchId)
            if (patch.hidden != 0) sb.append(" [hidden]")
            if (targets.isNotEmpty()) sb.append(" -> ").append(targets.joinToString(", "))
            if (patch.ctdContribution != 0) sb.append("  CTD+").append(patch.ctdContribution)
            sb.appendLine()
        }
        if (disabledPatchIds.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Disabled patches (${disabledPatchIds.size}):")
            disabledPatchIds.sorted().forEach { sb.append("  ").appendLine(it) }
        }
        return sb.toString()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
}
