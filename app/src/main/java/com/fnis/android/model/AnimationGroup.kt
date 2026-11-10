package com.fnis.android.model

data class AnimationGroup(
    val groupId: Int,
    val name: String,
    val numAnims: Int,
    val hkxFile: String,
    val flags: String
)
