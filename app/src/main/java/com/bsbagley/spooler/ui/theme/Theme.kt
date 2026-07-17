package com.bsbagley.spooler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand indigo, matching the launcher icon background (#3949AB) — with
// light/dark tonal variants hand-picked in the same style Material 3's own
// palette generator would produce from that seed color.
private val BrandIndigo = Color(0xFF3949AB)
private val BrandIndigoLight = Color(0xFFBEC2FF)
private val BrandIndigoContainerLight = Color(0xFF93BBFA)
private val BrandIndigoOnContainerLight = Color(0xFF0A1440)
private val BrandIndigoContainerDark = Color(0xFF93BBFA)
private val BrandIndigoOnContainerDark = Color(0xFF0A1440)
private val BrandIndigoOnDark = Color(0xFF1A2478)

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = BrandIndigoContainerLight,
    onPrimaryContainer = BrandIndigoOnContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandIndigoLight,
    onPrimary = BrandIndigoOnDark,
    primaryContainer = BrandIndigoContainerDark,
    onPrimaryContainer = BrandIndigoOnContainerDark,
)

/**
 * Material 3 with a fixed palette seeded from the launcher icon's indigo,
 * not Material You dynamic color — the app's accent should match its own
 * branding rather than shifting with whatever the phone's wallpaper is.
 */
@Composable
fun SpoolerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
