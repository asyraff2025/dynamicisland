package com.acapan.island

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)

    /** Distance from top of screen, in dp. Tune so the pill sits over your camera hole. */
    fun yOffset(c: Context) = sp(c).getInt("y", 6)
    fun setYOffset(c: Context, v: Int) = sp(c).edit().putInt("y", v).apply()

    /** Pill width while showing a notification (compact), in dp. */
    fun width(c: Context) = sp(c).getInt("w", 180)
    fun setWidth(c: Context, v: Int) = sp(c).edit().putInt("w", v).apply()

    /** Pill width while idle (always-on), in dp. */
    fun idleWidth(c: Context) = sp(c).getInt("iw", 48)
    fun setIdleWidth(c: Context, v: Int) = sp(c).edit().putInt("iw", v).apply()

    /** Debug: draw the idle pill in red so you can see where it is. */
    fun debugColor(c: Context) = sp(c).getBoolean("dbg", false)
    fun setDebugColor(c: Context, v: Boolean) = sp(c).edit().putBoolean("dbg", v).apply()

    /** Keep a small pill on screen all the time. */
    fun alwaysOn(c: Context) = sp(c).getBoolean("always", true)
    fun setAlwaysOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("always", v).apply()
}
