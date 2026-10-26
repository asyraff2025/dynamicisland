package com.acapan.island

import android.content.Context
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

data class IslandData(
    val appName: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val onOpen: (() -> Unit)? = null
)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
