package com.salon.nailtryon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SalonPink = Color(0xFFE91E63)
private val SalonRose = Color(0xFFFCE4EC)

private val LightColors = lightColorScheme(
    primary = SalonPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8E8),
    secondary = Color(0xFF7B4F72),
    surface = Color.White,
    background = SalonRose,
)

@Composable
fun NailTryOnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
