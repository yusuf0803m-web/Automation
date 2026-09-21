package com.pelita.autocontinue.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFFFB300)
private val AmberDark = Color(0xFF8A5A00)

private val LightColors = lightColorScheme(
    primary = AmberDark,
    secondary = Color(0xFF4A5568),
)

private val DarkColors = darkColorScheme(
    primary = Amber,
    secondary = Color(0xFFA0AEC0),
)

@Composable
fun PelitaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
