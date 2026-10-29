package com.wenqu.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val BrandBlue = Color(0xFF1A5CFF)
val BrandCyan = Color(0xFF06B6D4)
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF0A1E4E), Color(0xFF1D4ED8), Color(0xFF06B6D4)))

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2472),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF4F7FE),
    onBackground = Color(0xFF10182B),
    surface = Color.White,
    onSurface = Color(0xFF10182B),
    surfaceVariant = Color(0xFFE6ECF9),
    onSurfaceVariant = Color(0xFF4A5568),
    outline = Color(0xFFC3CEE6),
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1E4E),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF062A30),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF0C1220),
    onBackground = Color(0xFFE5ECFA),
    surface = Color(0xFF131A2E),
    onSurface = Color(0xFFE5ECFA),
    surfaceVariant = Color(0xFF1C2440),
    onSurfaceVariant = Color(0xFF9DB0D3),
    outline = Color(0xFF33406B),
    error = Color(0xFFF87171)
)

@Composable
fun WenquTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        0 -> isSystemInDarkTheme()
        1 -> false
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
