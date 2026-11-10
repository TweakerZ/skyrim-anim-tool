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

    data class ExportResult(
        val copied: List<String>,
        val errors: List<String>,
        val skipped: List<String> = emptyList(),
        val behaviorsSkipped: Int = 0,
    )

    private val outputDir = File(context.filesDir, "output")

    fun initialize(): Boolean {
        outputDir.mkdirs()
        // Behavior artifacts from a previous run were produced by an older
        // generator and may not pass today's validation, so never let them be
        // exported without a fresh Generate.
        outputDir.listFiles { file -> file.isFile && file.name.endsWith(".hkx", ignoreCase = true) }
            ?.forEach { it.delete() }
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
     * Copies the verified generated files into the Skyrim Data folder.
     *
     * Behavior .hkx and animation data files are never copied. A behavior that
     * passes the structural gate can still differ from the one the engine
     * expects, and a wrong graph crashes Skyrim on the first frame; the
     * animation data files have a record layout the template engine does not
     * reproduce yet. The mod ships working baselines, so they stay untouched
     * and only scripts and the patch report are installed.
     */
    fun exportToData(dataDir: File): ExportResult {
        val copied = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var behaviorsSkipped = 0
        val backupRoot = File(
            dataDir,
            "tools/GenerateFNIS_for_Users/temporary_logs/backup_${fileTimestamp()}"
        )

        outputDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (isUnverifiedAnimationData(file.name)) {
                skipped += file.name
                return@forEach
            }
            if (file.name.endsWith(".pex", ignoreCase = true) && !looksLikePex(file)) {
                errors += "${file.name}: not a Papyrus executable, skipped"
                return@forEach
            }
            if (file.name.endsWith(".hkx", ignoreCase = true)) {
                behaviorsSkipped++
                return@forEach
            }

            val dest = destinationFor(file.name, dataDir) ?: return@forEach
            try {
                dest.parentFile?.mkdirs()
                if (dest.isFile) {
                    val backup = File(backupRoot, dest.name)
                    backup.parentFile?.mkdirs()
                    dest.copyTo(backup, overwrite = true)
                }
                file.copyTo(dest, overwrite = true)
                copied += dest.absolutePath
            } catch (e: Exception) {
                errors += "${file.name}: ${e.message}"
            }
        }

        return ExportResult(copied, errors, skipped, behaviorsSkipped)
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

    private fun isUnverifiedAnimationData(fileName: String): Boolean =
        fileName.equals("animationdatasinglefile.txt", ignoreCase = true) ||
            fileName.equals("animationsetdatasinglefile.txt", ignoreCase = true)

    /**
     * Papyrus executables start with the pex magic 0xFA57C0DE.
     */
    private fun looksLikePex(file: File): Boolean = hasMagic(file, 0xFA, 0x57, 0xC0, 0xDE)

    private fun hasMagic(file: File, vararg magic: Int): Boolean {
        if (file.length() < magic.size) return false
        return try {
            val header = ByteArray(magic.size)
            file.inputStream().use { it.read(header) }
            magic.indices.all { header[it] == magic[it].toByte() }
        } catch (e: Exception) {
            false
        }
    }

    private fun destinationFor(fileName: String, dataDir: File): File? {
        return when {
            fileName.endsWith(".pex") ->
                File(dataDir, "scripts/$fileName")
            fileName == "FNIS_Patches.txt" ->
                File(dataDir, "tools/GenerateFNIS_for_Users/$fileName")
            else -> null
        }
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

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
}
