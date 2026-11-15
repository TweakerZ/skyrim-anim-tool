package com.fnis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fnis.android.R
import com.skyrimsuite.ui.SuiteBarAction
import com.skyrimsuite.ui.SuiteCard
import com.skyrimsuite.ui.SuiteCheckRow
import com.skyrimsuite.ui.SuiteChip
import com.skyrimsuite.ui.SuiteChipTone
import com.skyrimsuite.ui.SuiteEmptyState
import com.skyrimsuite.ui.SuiteLogCard
import com.skyrimsuite.ui.SuitePathRow
import com.skyrimsuite.ui.SuitePrimaryButton
import com.skyrimsuite.ui.SuiteSecondaryButton
import com.skyrimsuite.ui.SuitePermissionNotice
import com.skyrimsuite.ui.SuiteScreen
import com.skyrimsuite.ui.SuiteSectionHeader
import com.skyrimsuite.ui.SuiteSettingsSheet

/** One row of PatchList.txt, projected for the UI. */
data class PatchUi(
    val id: String,
    val description: String,
    val ctd: Int,
    val enabled: Boolean
)

@Composable
fun FnisScreen(
    folderPath: String?,
    storageGranted: Boolean,
    statusText: String,
    summaryText: String,
    zipOutputPath: String?,
    patches: List<PatchUi>,
    pscd: Boolean,
    instantExecute: Boolean,
    ctdCalculation: Boolean,
    busy: Boolean,
    logText: String,
    onPickFolder: () -> Unit,
    onPickZipOutput: () -> Unit,
    onGrantStorage: () -> Unit,
    onPscdChange: (Boolean) -> Unit,
    onInstantChange: (Boolean) -> Unit,
    onCtdChange: (Boolean) -> Unit,
    onTogglePatch: (String, Boolean) -> Unit,
    onGenerate: () -> Unit,
    onExport: () -> Unit,
    onClearLog: () -> Unit
) {
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var logExpanded by rememberSaveable { mutableStateOf(true) }

    val enabledPatches = patches.count { it.enabled }
    val readyLabel = stringResource(R.string.status_ready)

    val chips = buildList {
        add(
            SuiteChip(
                label = if (storageGranted) "Storage access" else "Storage required",
                tone = if (storageGranted) SuiteChipTone.Positive else SuiteChipTone.Error
            )
        )
        add(SuiteChip(statusText.ifBlank { readyLabel }))
        if (patches.isNotEmpty()) {
            add(SuiteChip("$enabledPatches/${patches.size} patches"))
        }
        if (folderPath.isNullOrBlank()) {
            add(SuiteChip("No Data folder", SuiteChipTone.Warning))
        }
    }

    SuiteScreen(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.app_subtitle),
        chips = chips,
        onSettings = { settingsVisible = true },
        primaryAction = SuiteBarAction(
            label = stringResource(R.string.btn_generate),
            onClick = onGenerate,
            enabled = folderPath != null && !busy
        ),
        secondaryActions = listOf(
            SuiteBarAction(
                label = stringResource(R.string.btn_export),
                onClick = onExport,
                enabled = !busy
            )
        ),
        busy = busy,
        busyText = statusText
    ) {
        SuitePermissionNotice(
            granted = storageGranted,
            title = "All-files access required",
            body = stringResource(R.string.storage_missing),
            grantLabel = stringResource(R.string.btn_grant_storage),
            onGrant = onGrantStorage
        )

        if (summaryText.isNotBlank()) {
            SuiteCard {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SuiteSectionHeader(
            title = stringResource(R.string.title_patches),
            subtitle = if (patches.isEmpty()) {
                stringResource(R.string.empty_patches_hint)
            } else {
                stringResource(R.string.patches_hint)
            }
        )

        if (patches.isEmpty()) {
            SuiteEmptyState(
                title = stringResource(R.string.empty_no_patches),
                body = stringResource(R.string.empty_patches_hint)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                patches.forEach { patch ->
                    val ctdLabel = stringResource(R.string.label_patch_ctd, patch.ctd)
                    SuiteCard {
                        SuiteCheckRow(
                            title = patch.id,
                            subtitle = buildString {
                                if (patch.description.isNotBlank()) {
                                    append(patch.description)
                                }
                                if (patch.ctd > 0) {
                                    if (isNotEmpty()) append("  ·  ")
                                    append(ctdLabel)
                                }
                            },
                            checked = patch.enabled,
                            onCheckedChange = { onTogglePatch(patch.id, it) },
                            enabled = !busy
                        )
                    }
                }
            }
        }

        SuiteSectionHeader(title = stringResource(R.string.label_options))

        SuiteCard {
            SuiteCheckRow(
                title = stringResource(R.string.label_pscd),
                checked = pscd,
                onCheckedChange = onPscdChange,
                enabled = !busy
            )
            SuiteCheckRow(
                title = stringResource(R.string.label_instant),
                checked = instantExecute,
                onCheckedChange = onInstantChange,
                enabled = !busy
            )
            SuiteCheckRow(
                title = stringResource(R.string.label_ctd),
                checked = ctdCalculation,
                onCheckedChange = onCtdChange,
                enabled = !busy
            )
        }

        SuiteLogCard(
            title = stringResource(R.string.title_log),
            log = logText,
            expanded = logExpanded,
            onToggle = { logExpanded = !logExpanded },
            emptyText = stringResource(R.string.logs_empty)
        )
    }

    SuiteSettingsSheet(
        visible = settingsVisible,
        onDismiss = { settingsVisible = false },
        title = stringResource(R.string.settings_title)
    ) {
        SuitePathRow(
            title = stringResource(R.string.label_data_folder),
            path = folderPath,
            onChange = onPickFolder,
            supportingText = stringResource(R.string.hint_data_folder)
        )

        SuitePathRow(
            title = stringResource(R.string.label_zip_output),
            path = zipOutputPath,
            onChange = onPickZipOutput,
            supportingText = stringResource(R.string.hint_zip_output)
        )

        if (!storageGranted) {
            SuitePrimaryButton(
                text = stringResource(R.string.btn_grant_storage),
                onClick = onGrantStorage
            )
        }

        SuiteSecondaryButton(
            text = stringResource(R.string.btn_clear_log),
            onClick = onClearLog
        )

        Text(
            text = stringResource(R.string.log_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
