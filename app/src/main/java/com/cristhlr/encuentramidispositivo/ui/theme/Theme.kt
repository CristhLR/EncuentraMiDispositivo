package com.cristhlr.encuentramidispositivo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    secondary = Color(0xFF56647A),
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    error = Color(0xFFBA1A1A),
)

@Composable
fun EncuentraMiDispositivoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}

