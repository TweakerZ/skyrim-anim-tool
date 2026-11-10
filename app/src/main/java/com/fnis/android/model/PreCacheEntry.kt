package com.fnis.android.model

data class PreCacheEntry(
    val modName: String,
    val referencePathCrc: String,
    val referenceFileCrc: String,
    val appearances: String? = null,
    val customPathCrc: String,
    val numCustomAnims: Int,
    val customFileCrcs: List<String>
)
