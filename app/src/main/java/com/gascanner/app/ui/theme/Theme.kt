package com.gascanner.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Gold = Color(0xFFE8C46A)
private val DeepIndigo = Color(0xFF221E33)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF2B2200),
    secondary = Color(0xFFB9B2E0),
    background = Color(0xFF14121A),
    surface = Color(0xFF14121A),
    surfaceVariant = DeepIndigo,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5C00),
    secondary = Color(0xFF55507A),
)

@Composable
fun GATheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
