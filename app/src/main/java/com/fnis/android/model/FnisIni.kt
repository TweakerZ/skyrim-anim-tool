package com.fnis.android.model

data class FnisIni(
    val pscd: Boolean = false,
    val redirectFiles: String? = null,
    val instantExecute: Boolean = false,
    val loadCtdCalculation: Boolean = true
)
