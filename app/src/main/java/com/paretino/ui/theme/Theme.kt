package com.paretino.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1C6ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF8A5A2B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6DFC4),
    onSecondaryContainer = Color(0xFF2E1600),
    tertiary = Color(0xFF8E3FA8),
    tertiaryContainer = Color(0xFFF6D9FF),
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFBF7F2),
    surfaceContainer = Color(0xFFF2ECE4),
    surfaceContainerHigh = Color(0xFFECE5DC),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF002F6C),
    primaryContainer = Color(0xFF00449A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFE9BF93),
    onSecondary = Color(0xFF462A0B),
    secondaryContainer = Color(0xFF604020),
    onSecondaryContainer = Color(0xFFF6DFC4),
    tertiary = Color(0xFFE7B3FF),
    tertiaryContainer = Color(0xFF6E2588),
    background = Color(0xFF1A1714),
    surface = Color(0xFF1A1714),
    surfaceContainer = Color(0xFF26221E),
    surfaceContainerHigh = Color(0xFF312C27),
)

@Composable
fun ParetinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Typography(), content = content)
}
