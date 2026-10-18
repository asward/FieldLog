package io.github.asward.fieldlog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF2F5D50)
private val GreenLight = Color(0xFF8CC7B3)
private val Orange = Color(0xFFE07A2E)
private val Paper = Color(0xFFFFF8F0)
private val Ink = Color(0xFF1E1B18)

private val Light = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Orange,
    onSecondary = Color.White,
    tertiary = Orange,
    background = Paper,
    surface = Paper,
    onBackground = Ink,
    onSurface = Ink,
)

private val Dark = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF0B2A21),
    secondary = Color(0xFFF2A065),
    onSecondary = Color(0xFF3A1A00),
    tertiary = Color(0xFFF2A065),
    background = Color(0xFF141311),
    surface = Color(0xFF141311),
)

@Composable
fun FieldLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
