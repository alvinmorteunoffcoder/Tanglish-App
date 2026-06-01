package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GeoAccentLight,
    onPrimary = GeoAccentDark,
    secondary = GeoKeyFunc,
    onSecondary = GeoTextPrimary,
    tertiary = GeoAccentLight,
    background = GeoBackground,
    surface = GeoSurface,
    onBackground = GeoTextPrimary,
    onSurface = GeoTextPrimary,
    surfaceVariant = GeoKeyLetter,
    onSurfaceVariant = GeoTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = GeoAccentDark,
    onPrimary = GeoAccentLight,
    secondary = GeoKeyFunc,
    onSecondary = GeoTextSecondary,
    tertiary = GeoAccentDark,
    background = GeoBackground,
    surface = GeoSurface,
    onBackground = GeoTextPrimary,
    onSurface = GeoTextPrimary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force visual dark theme for high fidelity Gboard styling
    dynamicColor: Boolean = false, // Disable dynamic colors key to guarantee strict Geometric Balance palette
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
