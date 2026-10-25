package com.duckreader.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The two themes, both cut from the duck's own colours: a page of white paper warmed a little by
 * the sun, and a night made of brown-black rather than blue.
 *
 * Daylight is near-white paper with ink that is warm charcoal rather than black, and amber — the
 * duck's beak — for anything the reader can act on. The amber is deep enough to be read as text on
 * white (about 5:1), because a colour that only works as a fill is a colour that has to be
 * explained twice. Night is the same drawing after dark: warm near-black, with the amber lifted so
 * it stays legible.
 */
internal val DayColors = lightColorScheme(
    primary = Color(0xFFB45309), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFDEBC8), onPrimaryContainer = Color(0xFF7A3E04),
    secondary = Color(0xFF6B5F4B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3EAD8), onSecondaryContainer = Color(0xFF3A3226),
    background = Color(0xFFFFFDF8), onBackground = Color(0xFF241E16),
    surface = Color(0xFFFFFDF8), onSurface = Color(0xFF241E16),
    surfaceVariant = Color(0xFFF6EFE3), onSurfaceVariant = Color(0xFF6B6154),
    outline = Color(0xFFA79A88), outlineVariant = Color(0xFFE6DCCB),
)

internal val NightColors = darkColorScheme(
    primary = Color(0xFFF2C063), onPrimary = Color(0xFF3A2606),
    primaryContainer = Color(0xFF4A3617), onPrimaryContainer = Color(0xFFFBE3B4),
    secondary = Color(0xFFD8C6A8), onSecondary = Color(0xFF3A2F1C),
    secondaryContainer = Color(0xFF3B3427), onSecondaryContainer = Color(0xFFEFE4CE),
    background = Color(0xFF16130E), onBackground = Color(0xFFF2EADA),
    surface = Color(0xFF16130E), onSurface = Color(0xFFF2EADA),
    surfaceVariant = Color(0xFF26221A), onSurfaceVariant = Color(0xFFC9BCA6),
    outline = Color(0xFF8B7E6A), outlineVariant = Color(0xFF3A342A),
)
