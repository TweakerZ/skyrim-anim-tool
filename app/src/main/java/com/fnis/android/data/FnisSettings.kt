package com.fnis.android.data

import android.content.Context

/**
 * Persists the user's generator options and per-patch enable/disable state.
 * A null option means "not overridden yet", so the value from FNIS.ini wins.
 */
class FnisSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var dataPath: String?
        get() = prefs.getString(KEY_DATA_PATH, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DATA_PATH) else putString(KEY_DATA_PATH, value)
            }.apply()
        }

    var pscd: Boolean?
        get() = boolOrNull(KEY_PSCD)
        set(value) = putBool(KEY_PSCD, value)

    var instantExecute: Boolean?
        get() = boolOrNull(KEY_INSTANT)
        set(value) = putBool(KEY_INSTANT, value)

    var loadCtdCalculation: Boolean?
        get() = boolOrNull(KEY_LOAD_CTD)
        set(value) = putBool(KEY_LOAD_CTD, value)

    fun isPatchEnabled(patchId: String): Boolean = patchId !in disabledPatches()

    fun setPatchEnabled(patchId: String, enabled: Boolean) {
        val disabled = disabledPatches().toMutableSet()
        if (enabled) disabled.remove(patchId) else disabled.add(patchId)
        prefs.edit().putStringSet(KEY_DISABLED_PATCHES, disabled).apply()
    }

    fun disabledPatches(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_PATCHES, emptySet())?.toSet() ?: emptySet()

    fun clearPatchSelection() {
        prefs.edit().remove(KEY_DISABLED_PATCHES).apply()
    }

    private fun boolOrNull(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    private fun putBool(key: String, value: Boolean?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putBoolean(key, value)
        }.apply()
    }

    companion object {
        private const val PREFS = "fnis_prefs"
        private const val KEY_DATA_PATH = "data_path"
        private const val KEY_PSCD = "opt_pscd"
        private const val KEY_INSTANT = "opt_instant"
        private const val KEY_LOAD_CTD = "opt_load_ctd"
        private const val KEY_DISABLED_PATCHES = "disabled_patches"
    }
}
