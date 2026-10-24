package com.galaxywatch.monitor.ui

import androidx.compose.ui.graphics.Color

val AmoledBlack = Color(0xFF000000)
val CardBackground = Color(0xFF111216)
val TrackBackground = Color(0x20FFFFFF)

// Accent Colors
val GpuCyan = Color(0xFF00F3FF)
val GpuBlue = Color(0xFF0066FF)
val CpuCoral = Color(0xFFFF3366)
val CpuPink = Color(0xFFFF0055)
val RamPurple = Color(0xFFA855F7)

// Status Colors
val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFF8B92A5)
val WarnAmber = Color(0xFFFFB020)
val CritOrange = Color(0xFFFF5500)
val CritRed = Color(0xFFFF0022)

/**
 * Returns progressive color based on percentage:
 * 0-67%: Pure White
 * 68-79%: Warm Amber
 * 80-89%: Neon Orange
 * 90%+: Bright Warning Red
 */
fun getProgressiveColor(percent: Int, defaultColor: Color = TextWhite): Color {
    return when {
        percent >= 90 -> CritRed
        percent >= 80 -> CritOrange
        percent >= 68 -> WarnAmber
        else -> defaultColor
    }
}
