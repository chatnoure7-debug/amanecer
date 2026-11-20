package com.reloj.despertar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF2B1700),
    secondary = Color(0xFFE0B98A),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFEDE7DD),
    surface = Color(0xFF171923),
    onSurface = Color(0xFFEDE7DD),
    surfaceVariant = Color(0xFF22252F),
    onSurfaceVariant = Color(0xFFB9B2A6),
)

@Composable
fun AmanecerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
