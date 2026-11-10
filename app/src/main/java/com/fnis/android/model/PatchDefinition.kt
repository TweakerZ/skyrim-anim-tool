package com.fnis.android.model

data class PatchDefinition(
    val patchId: String,
    val hidden: Int,
    val numBones: Int,
    val requiredBehaviorPattern: String,
    val description: String,
    val modInstallCheckFile: String? = null,
    val ctdContribution: Int = 0
)
