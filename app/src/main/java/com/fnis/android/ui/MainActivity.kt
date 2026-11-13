package com.fnis.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fnis.android.R
import com.fnis.android.data.FnisDataRepository
import com.fnis.android.data.FnisSettings
import com.fnis.android.data.IniWriter
import com.fnis.android.data.StoragePaths
import com.fnis.android.model.FnisIni
import com.fnis.android.model.PatchDefinition
import com.fnis.android.service.FnisGeneratorService
import com.fnis.android.service.TemplateProcessor
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var service: FnisGeneratorService
    private lateinit var settings: FnisSettings

    private lateinit var tvFolderPath: TextView
    private lateinit var tvStorageStatus: TextView
    private lateinit var btnGrantStorage: MaterialButton
    private lateinit var cbPscd: CheckBox
    private lateinit var cbInstant: CheckBox
    private lateinit var cbCtd: CheckBox
    private lateinit var btnGenerate: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnSelectFolder: MaterialButton
    private lateinit var progressGenerate: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var listPatches: LinearLayout
    private lateinit var tvPatchCount: TextView
    private lateinit var layoutEmptyPatches: View
    private lateinit var tvLogContent: TextView
    private lateinit var scrollLog: ScrollView

    private var selectedFolder: File? = null
    private var currentIni: FnisIni? = null
    private var loadedPath: String? = null
    private var suppressOptionCallbacks = false
    private var pendingZip: File? = null

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
            btnExport.isEnabled = true
            tvStatus.text = getString(R.string.status_ready)
            logMessage(getString(R.string.msg_zip_cancelled))
        } else {
            saveZip(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        service = FnisGeneratorService(this)
        service.initialize()
        settings = FnisSettings(this)

        bindViews()
        setupListeners()

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
            tvFolderPath.text = folder.absolutePath
            loadData(folder)
        }
    }

    private fun bindViews() {
        tvFolderPath = findViewById(R.id.tv_data_folder_path)
        tvStorageStatus = findViewById(R.id.tv_storage_status)
        btnGrantStorage = findViewById(R.id.btn_grant_storage)
        cbPscd = findViewById(R.id.cb_pscd)
        cbInstant = findViewById(R.id.cb_instant)
        cbCtd = findViewById(R.id.cb_ctd)
        btnGenerate = findViewById(R.id.btn_generate)
        btnExport = findViewById(R.id.btn_export)
        btnSelectFolder = findViewById(R.id.btn_select_data_folder)
        progressGenerate = findViewById(R.id.progress_generate)
        tvStatus = findViewById(R.id.tv_status)
        tvSummary = findViewById(R.id.tv_summary)
        listPatches = findViewById(R.id.list_patches)
        tvPatchCount = findViewById(R.id.tv_patch_count)
        layoutEmptyPatches = findViewById(R.id.layout_empty_patches)
        tvLogContent = findViewById(R.id.tv_log_content)
        scrollLog = findViewById(R.id.scroll_log)
    }

    private fun setupListeners() {
        btnGrantStorage.setOnClickListener {
            StoragePaths.requestAllFilesAccess(this)
        }

        btnSelectFolder.setOnClickListener {
            openFolderPicker()
        }

        btnGenerate.setOnClickListener {
            if (selectedFolder == null) {
                tvFolderPath.error = getString(R.string.msg_no_data_folder)
                return@setOnClickListener
            }
            runGeneration()
        }

        btnExport.setOnClickListener {
            createModZip()
        }

        cbPscd.setOnCheckedChangeListener { _, isChecked ->
            if (suppressOptionCallbacks) return@setOnCheckedChangeListener
            currentIni = (currentIni ?: FnisIni()).copy(pscd = isChecked)
            settings.pscd = isChecked
            persistIni()
        }
        cbInstant.setOnCheckedChangeListener { _, isChecked ->
            if (suppressOptionCallbacks) return@setOnCheckedChangeListener
            currentIni = (currentIni ?: FnisIni()).copy(instantExecute = isChecked)
            settings.instantExecute = isChecked
            persistIni()
        }
        cbCtd.setOnCheckedChangeListener { _, isChecked ->
            if (suppressOptionCallbacks) return@setOnCheckedChangeListener
            currentIni = (currentIni ?: FnisIni()).copy(loadCtdCalculation = isChecked)
            settings.loadCtdCalculation = isChecked
            persistIni()
        }

        findViewById<MaterialButton>(R.id.btn_clear_log).setOnClickListener {
            clearLog()
        }
    }

    private fun refreshStorageStatus() {
        val granted = StoragePaths.hasAllFilesAccess(this)
        tvStorageStatus.text = getString(
            if (granted) R.string.storage_granted else R.string.storage_missing
        )
        btnGrantStorage.visibility = if (granted) View.GONE else View.VISIBLE
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
            tvFolderPath.text = folder.absolutePath
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
        tvFolderPath.text = dataDir.absolutePath
        tvFolderPath.error = null
        logMessage(getString(R.string.msg_data_folder, dataDir.absolutePath))

        if (!StoragePaths.hasAllFilesAccess(this)) {
            logMessage(getString(R.string.storage_missing))
            tvStatus.text = getString(R.string.status_waiting_access)
            tvSummary.text = getString(R.string.storage_missing)
            StoragePaths.requestAllFilesAccess(this)
            updateUiState()
            return
        }

        loadData(dataDir)
        updateUiState()
    }

    private fun loadData(dir: File) {
        tvStatus.text = getString(R.string.status_loading)
        tvSummary.text = dir.absolutePath
        progressGenerate.visibility = View.VISIBLE

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
                progressGenerate.visibility = View.GONE
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
                    suppressOptionCallbacks = true
                    cbPscd.isChecked = currentIni?.pscd ?: false
                    cbInstant.isChecked = currentIni?.instantExecute ?: false
                    cbCtd.isChecked = currentIni?.loadCtdCalculation ?: true
                    suppressOptionCallbacks = false
                    tvStatus.text = getString(R.string.status_ready)
                    tvSummary.text = buildString {
                        append(FnisDataRepository.patches.count { it.hidden == 0 && it.numBones == 0 })
                            .append(" patches · ")
                        append(FnisDataRepository.groups.size).append(" groups · ")
                        append(FnisDataRepository.templates.size).append(" templates")
                    }
                    logMessage(getString(R.string.msg_data_loaded, dir.absolutePath))
                    loadPatches()
                } else {
                    tvStatus.text = getString(R.string.status_error)
                    tvSummary.text = report.errors.firstOrNull() ?: getString(R.string.msg_no_data_folder)
                    loadPatches()
                }
                updateUiState()
            }
        }.start()
    }

    private fun loadPatches() {
        val patches = FnisDataRepository.patches.filter { it.hidden == 0 && it.numBones == 0 }
        listPatches.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (patch in patches) {
            val item = inflater.inflate(R.layout.item_patch, listPatches, false)
            item.findViewById<TextView>(R.id.tv_patch_id).text = patch.patchId
            item.findViewById<TextView>(R.id.tv_patch_description).text = patch.description
            item.findViewById<TextView>(R.id.tv_patch_ctd).text =
                getString(R.string.label_patch_ctd, patch.ctdContribution)

            val cbEnabled = item.findViewById<CheckBox>(R.id.cb_patch_enabled)
            cbEnabled.isChecked = settings.isPatchEnabled(patch.patchId)
            cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                settings.setPatchEnabled(patch.patchId, isChecked)
                updatePatchCount(patches)
            }
            listPatches.addView(item)
        }

        updatePatchCount(patches)
        layoutEmptyPatches.visibility = if (patches.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updatePatchCount(patches: List<PatchDefinition>) {
        val enabled = patches.count { settings.isPatchEnabled(it.patchId) }
        tvPatchCount.text = getString(R.string.label_patch_count, patches.size) +
            " · " + getString(R.string.label_patch_enabled, enabled, patches.size)
    }

    private fun runGeneration() {
        if (!FnisDataRepository.loaded) {
            tvStatus.text = getString(R.string.status_waiting_access)
            tvSummary.text = getString(R.string.storage_missing)
            logMessage(getString(R.string.msg_no_fnis_data))
            return
        }

        val ini = currentIni ?: FnisDataRepository.ini
        pendingZip?.delete()
        pendingZip = null
        btnGenerate.isEnabled = false
        btnExport.isEnabled = false
        btnGenerate.text = getString(R.string.status_generating)
        progressGenerate.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_generating)
        tvSummary.text = ""

        val groups = FnisDataRepository.groups
        val selectablePatches = FnisDataRepository.patches.filter {
            it.hidden == 0 && it.numBones == 0
        }
        val patches = selectablePatches.filter { settings.isPatchEnabled(it.patchId) }
        val disabledIds = selectablePatches
            .filterNot { settings.isPatchEnabled(it.patchId) }
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
                    patches,
                    processor,
                    disabledIds
                ) { message ->
                    Handler(Looper.getMainLooper()).post {
                        if (isFinishing || isDestroyed) return@post
                        tvStatus.text = message
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    btnGenerate.isEnabled = true
                    btnGenerate.text = getString(R.string.btn_generate)
                    btnExport.isEnabled = true
                    progressGenerate.visibility = View.GONE
                    tvStatus.text = getString(R.string.status_complete)
                    val skipped = generated.count {
                        it.contains("SKIPPED") || it.contains("CONVERSION FAILED")
                    }
                    tvSummary.text = if (skipped > 0) {
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
                // template would otherwise leave the button disabled forever.
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    btnGenerate.isEnabled = true
                    btnGenerate.text = getString(R.string.btn_generate)
                    progressGenerate.visibility = View.GONE
                    tvStatus.text = getString(R.string.status_error)
                    tvSummary.text = e.message ?: ""
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

        btnExport.isEnabled = false
        tvStatus.text = getString(R.string.status_building_zip)
        tvSummary.text = ""

        Thread {
            try {
                val result = service.buildModZip()
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    pendingZip = result.file
                    tvStatus.text = getString(R.string.status_choose_zip_location)
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
                        btnExport.isEnabled = true
                        tvStatus.text = getString(R.string.status_error)
                        logMessage(getString(R.string.msg_zip_failed, e.message.orEmpty()))
                    }
                }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    btnExport.isEnabled = true
                    tvStatus.text = getString(R.string.status_error)
                    tvSummary.text = e.message ?: ""
                    logMessage(getString(R.string.msg_zip_failed, e.message.orEmpty()))
                }
            }
        }.start()
    }

    private fun saveZip(uri: Uri) {
        val zip = pendingZip ?: return
        tvStatus.text = getString(R.string.status_saving_zip)
        tvSummary.text = uri.toString()

        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    zip.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("The selected location could not be opened")

                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    btnExport.isEnabled = true
                    tvStatus.text = getString(R.string.status_complete)
                    tvSummary.text = getString(R.string.label_zip_saved)
                    logMessage(getString(R.string.msg_zip_saved, uri.toString()))
                    logMessage(getString(R.string.msg_zip_install))
                    zip.delete()
                    pendingZip = null
                }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed) return@post
                    btnExport.isEnabled = true
                    tvStatus.text = getString(R.string.status_error)
                    tvSummary.text = e.message ?: ""
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
        btnGenerate.isEnabled = hasFolder
        if (!hasFolder) {
            tvStatus.text = getString(R.string.status_ready)
            tvSummary.text = getString(R.string.summary_empty)
        }
    }

    private fun appendLog(message: String) {
        val current = tvLogContent.text?.toString().orEmpty()
        val base = if (current == getString(R.string.logs_empty)) "" else current
        tvLogContent.text = base + message + "\n"
        // Scroll only the log box. fullScroll(FOCUS_DOWN) moves focus into the
        // selectable log text, and the outer ScrollView then scrolls the whole
        // page down to reveal that focused view.
        scrollLog.post {
            val content = scrollLog.getChildAt(0) ?: return@post
            scrollLog.scrollTo(0, maxOf(0, content.height - scrollLog.height))
        }
    }

    private fun logMessage(message: String) {
        appendLog(message)
    }

    private fun clearLog() {
        tvLogContent.text = getString(R.string.logs_empty)
        scrollLog.post { scrollLog.scrollTo(0, 0) }
    }
}
