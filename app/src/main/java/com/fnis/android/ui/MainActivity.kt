package com.fnis.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fnis.android.R
import com.fnis.android.data.FnisDataRepository
import com.fnis.android.data.FnisSettings
import com.fnis.android.data.IniWriter
import com.fnis.android.data.StoragePaths
import com.fnis.android.model.FnisIni
import com.fnis.android.service.FnisGeneratorService
import com.fnis.android.service.TemplateProcessor
import com.skyrimsuite.ui.theme.SkyrimSuiteTheme
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var service: FnisGeneratorService
    private lateinit var settings: FnisSettings

    /* Compose-observed state */
    private var folderPath by mutableStateOf<String?>(null)
    private var storageGranted by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var summaryText by mutableStateOf("")
    private var patches by mutableStateOf<List<PatchUi>>(emptyList())
    private var pscd by mutableStateOf(false)
    private var instantExecute by mutableStateOf(false)
    private var ctdCalculation by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var logText by mutableStateOf("")

    /* Local state */
    private var selectedFolder: File? = null
    private var currentIni: FnisIni? = null
    private var loadedPath: String? = null
    private var pendingZip: File? = null
    private val logBuffer = StringBuilder()

    private val folderLauncher = registerForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistPermission(uri)
            val folder = StoragePaths.resolve(this, uri)
            if (folder == null) {
                logMessage(getString(R.string.msg_path_unresolved))
                promptForPath("/storage/emulated/0/")
            } else {
                applyFolder(folder)
            }
        }
    }

    private val zipLauncher = registerForActivityResult(CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            pendingZip?.delete()
            pendingZip = null
            statusText = getString(R.string.status_ready)
            logMessage(getString(R.string.msg_zip_cancelled))
        } else {
            saveZip(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        service = FnisGeneratorService(this)
        service.initialize()
        settings = FnisSettings(this)

        readPreferences()

        setContent {
            SkyrimSuiteTheme {
                FnisScreen(
                    folderPath = folderPath,
                    storageGranted = storageGranted,
                    statusText = statusText,
                    summaryText = summaryText,
                    patches = patches,
                    pscd = pscd,
                    instantExecute = instantExecute,
                    ctdCalculation = ctdCalculation,
                    busy = busy,
                    logText = logText,
                    onPickFolder = { openFolderPicker() },
                    onGrantStorage = { StoragePaths.requestAllFilesAccess(this) },
                    onPscdChange = { checked ->
                        pscd = checked
                        currentIni = (currentIni ?: FnisIni()).copy(pscd = checked)
                        settings.pscd = checked
                        persistIni()
                    },
                    onInstantChange = { checked ->
                        instantExecute = checked
                        currentIni = (currentIni ?: FnisIni()).copy(instantExecute = checked)
                        settings.instantExecute = checked
                        persistIni()
                    },
                    onCtdChange = { checked ->
                        ctdCalculation = checked
                        currentIni = (currentIni ?: FnisIni()).copy(loadCtdCalculation = checked)
                        settings.loadCtdCalculation = checked
                        persistIni()
                    },
                    onTogglePatch = { id, enabled ->
                        settings.setPatchEnabled(id, enabled)
                        patches = patches.map {
                            if (it.id == id) it.copy(enabled = enabled) else it
                        }
                    },
                    onGenerate = { runGeneration() },
                    onExport = { createModZip() },
                    onClearLog = { clearLog() }
                )
            }
        }

        updateUiState()
        restoreSavedFolder()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageStatus()

        val saved = settings.dataPath ?: return
        if (loadedPath == saved) return
        if (!StoragePaths.hasAllFilesAccess(this)) return
        val folder = File(saved)
        if (folder.isDirectory) {
            selectedFolder = folder
            folderPath = folder.absolutePath
            loadData(folder)
        }
    }

    private fun readPreferences() {
        storageGranted = StoragePaths.hasAllFilesAccess(this)
        pscd = settings.pscd ?: false
        instantExecute = settings.instantExecute ?: false
        ctdCalculation = settings.loadCtdCalculation ?: true
    }

    private fun refreshStorageStatus() {
        storageGranted = StoragePaths.hasAllFilesAccess(this)
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers only grant read access; the real path is what matters.
        }
    }

    private fun openFolderPicker() {
        try {
            folderLauncher.launch(null)
        } catch (e: Exception) {
            logMessage(getString(R.string.msg_picker_failed, e.message.orEmpty()))
            promptForPath("/storage/emulated/0/")
        }
    }

    private fun restoreSavedFolder() {
        val saved = settings.dataPath ?: return
        val folder = File(saved)
        if (folder.isDirectory) {
            selectedFolder = folder
            folderPath = folder.absolutePath
            updateUiState()
        } else {
            settings.dataPath = null
        }
    }

    private fun applyFolder(picked: File) {
        if (!picked.isDirectory) {
            logMessage(getString(R.string.msg_folder_missing, picked.path))
            return
        }

        // Users often pick the Skyrim root instead of Data.
        val dataDir = when {
            picked.name.equals("Data", ignoreCase = true) -> picked
            File(picked, "Data").isDirectory -> File(picked, "Data")
            else -> picked
        }

        selectedFolder = dataDir
        settings.dataPath = dataDir.absolutePath
        folderPath = dataDir.absolutePath
        logMessage(getString(R.string.msg_data_folder, dataDir.absolutePath))

        if (!StoragePaths.hasAllFilesAccess(this)) {
            storageGranted = false
            logMessage(getString(R.string.storage_missing))
            statusText = getString(R.string.status_waiting_access)
            summaryText = getString(R.string.storage_missing)
            StoragePaths.requestAllFilesAccess(this)
            updateUiState()
            return
        }

        loadData(dataDir)
        updateUiState()
    }

    private fun loadData(dir: File) {
        statusText = getString(R.string.status_loading)
        summaryText = dir.absolutePath
        busy = true

        Thread {
            val report = try {
                FnisDataRepository.load(dir)
            } catch (e: Exception) {
                FnisDataRepository.LoadReport(
                    ok = false,
                    lines = emptyList(),
                    errors = listOf("Could not read FNIS data: ${e.message}")
                )
            }

            Handler(Looper.getMainLooper()).post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                report.lines.forEach { line -> logMessage(line) }
                report.errors.forEach { line -> logMessage(getString(R.string.msg_warning, line)) }

                if (report.ok) {
                    loadedPath = dir.absolutePath
                    val loadedIni = FnisDataRepository.ini
                    currentIni = loadedIni.copy(
                        pscd = settings.pscd ?: loadedIni.pscd,
                        instantExecute = settings.instantExecute ?: loadedIni.instantExecute,
                        loadCtdCalculation = settings.loadCtdCalculation ?: loadedIni.loadCtdCalculation
                    )
                    pscd = currentIni?.pscd ?: false
                    instantExecute = currentIni?.instantExecute ?: false
                    ctdCalculation = currentIni?.loadCtdCalculation ?: true

                    statusText = getString(R.string.status_ready)
                    summaryText = buildString {
                        append(FnisDataRepository.patches.count { it.hidden == 0 && it.numBones == 0 })
                            .append(" patches · ")
                        append(FnisDataRepository.groups.size).append(" groups · ")
                        append(FnisDataRepository.templates.size).append(" templates")
                    }
                    logMessage(getString(R.string.msg_data_loaded, dir.absolutePath))
                    loadPatches()
                } else {
                    statusText = getString(R.string.status_error)
                    summaryText = report.errors.firstOrNull() ?: getString(R.string.msg_no_data_folder)
                    loadPatches()
                }
                updateUiState()
            }
        }.start()
    }

    private fun loadPatches() {
        patches = FnisDataRepository.patches
            .filter { it.hidden == 0 && it.numBones == 0 }
            .map { patch ->
                PatchUi(
                    id = patch.patchId,
                    description = patch.description,
                    ctd = patch.ctdContribution,
                    enabled = settings.isPatchEnabled(patch.patchId)
                )
            }
    }

    private fun runGeneration() {
        if (!FnisDataRepository.loaded) {
            statusText = getString(R.string.status_waiting_access)
            summaryText = getString(R.string.storage_missing)
            logMessage(getString(R.string.msg_no_fnis_data))
            return
        }

        val ini = currentIni ?: FnisDataRepository.ini
        pendingZip?.delete()
        pendingZip = null
        busy = true
        statusText = getString(R.string.status_generating)
        summaryText = ""

        val groups = FnisDataRepository.groups
        val selectablePatches = FnisDataRepository.patches.filter {
            it.hidden == 0 && it.numBones == 0
        }
        val patchSelection = patches
        val enabledIds = patchSelection.filter { it.enabled }.map { it.id }.toSet()
        val selectedPatches = selectablePatches.filter { it.patchId in enabledIds }
        val disabledIds = selectablePatches
            .filterNot { it.patchId in enabledIds }
            .map { it.patchId }
            .toSet()

        if (disabledIds.isNotEmpty()) {
            logMessage(
                getString(
                    R.string.msg_skipping_patches,
                    disabledIds.size,
                    disabledIds.sorted().joinToString(", ")
                )
            )
        }

        Thread {
            try {
                val processor = TemplateProcessor()
                val generated = service.generateBehaviorFiles(
                    ini,
                    groups,
                    emptyList(),
                    selectedPatches,
                    processor,
                    disabledIds
                ) { message ->
                    Handler(Looper.getMainLooper()).post {
                        if (isFinishing || isDestroyed) return@post
                        statusText = message
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    busy = false
                    statusText = getString(R.string.status_complete)
                    val skipped = generated.count {
                        it.contains("SKIPPED") || it.contains("CONVERSION FAILED")
                    }
                    summaryText = if (skipped > 0) {
                        getString(R.string.label_generated_skipped, skipped)
                    } else {
                        getString(R.string.label_generated_count, generated.size)
                    }
                    logMessage(getString(R.string.status_complete))
                    logMessage(getString(R.string.msg_generation_complete))
                    for (file in generated) {
                        logMessage(getString(R.string.label_generated_file, file))
                    }
                    logMessage(getString(R.string.msg_generation_kept_in_app))
                }
            } catch (e: Throwable) {
                // Throwable, not Exception: an OutOfMemoryError on a large
                // template would otherwise leave the screen busy forever.
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    busy = false
                    statusText = getString(R.string.status_error)
                    summaryText = e.message ?: ""
                    logMessage(getString(R.string.status_error) + ": " + e.message)
                }
            }
        }.start()
    }

    /**
     * Packs the last generation into a mod archive, then lets the user pick
     * where to save it. A mod loader installs the archive like any other mod,
     * so the Skyrim Data folder is no longer written to directly.
     */
    private fun createModZip() {
        val outputDir = service.getOutputDir()
        if (!outputDir.exists() || outputDir.listFiles()?.isEmpty() == true) {
            logMessage(getString(R.string.msg_no_export_files))
            return
        }

        busy = true
        statusText = getString(R.string.status_building_zip)
        summaryText = ""

        Thread {
            try {
                val result = service.buildModZip()
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    busy = false
                    pendingZip = result.file
                    statusText = getString(R.string.status_choose_zip_location)
                    result.entries.forEach { entry ->
                        logMessage(getString(R.string.label_zip_entry, entry))
                    }
                    if (result.skipped.isNotEmpty()) {
                        logMessage(
                            getString(
                                R.string.msg_zip_skipped,
                                result.skipped.joinToString(", ")
                            )
                        )
                    }
                    try {
                        zipLauncher.launch(service.suggestedZipName())
                    } catch (e: Exception) {
                        pendingZip?.delete()
                        pendingZip = null
                        statusText = getString(R.string.status_error)
                        logMessage(getString(R.string.msg_zip_failed, e.message.orEmpty()))
                    }
                }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    busy = false
                    statusText = getString(R.string.status_error)
                    summaryText = e.message ?: ""
                    logMessage(getString(R.string.msg_zip_failed, e.message.orEmpty()))
                }
            }
        }.start()
    }

    private fun saveZip(uri: Uri) {
        val zip = pendingZip ?: return
        statusText = getString(R.string.status_saving_zip)
        summaryText = uri.toString()

        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    zip.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("The selected location could not be opened")

                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    statusText = getString(R.string.status_complete)
                    summaryText = getString(R.string.label_zip_saved)
                    logMessage(getString(R.string.msg_zip_saved, uri.toString()))
                    logMessage(getString(R.string.msg_zip_install))
                    zip.delete()
                    pendingZip = null
                }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    statusText = getString(R.string.status_error)
                    summaryText = e.message ?: ""
                    logMessage(getString(R.string.msg_zip_failed, e.message.orEmpty()))
                }
            }
        }.start()
    }

    private fun persistIni() {
        val dir = selectedFolder ?: return
        val ini = currentIni ?: return
        try {
            IniWriter.write(dir, ini)
        } catch (e: Exception) {
            logMessage(getString(R.string.msg_ini_write_failed, e.message.orEmpty()))
        }
    }

    private fun promptForPath(initial: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            setText(initial)
            hint = getString(R.string.dialog_path_hint)
        }

        val container = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_path_title)
            .setMessage(R.string.dialog_path_message)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) applyFolder(File(path))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateUiState() {
        val hasFolder = selectedFolder != null
        if (!hasFolder) {
            statusText = getString(R.string.status_ready)
            summaryText = getString(R.string.summary_empty)
        }
    }

    private fun logMessage(message: String) {
        logBuffer.append(message).append('\n')
        logText = logBuffer.toString()
    }

    private fun clearLog() {
        logBuffer.setLength(0)
        logText = ""
    }
}
