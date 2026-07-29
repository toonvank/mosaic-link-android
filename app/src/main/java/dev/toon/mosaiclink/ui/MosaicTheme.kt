package dev.toon.mosaiclink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B0D12)
private val Surface = Color(0xFF151820)
private val SurfaceHigh = Color(0xFF20242E)
private val Mint = Color(0xFFB9F6CA)
private val MintInk = Color(0xFF00391B)
private val Coral = Color(0xFFFFB4AB)

private val MosaicColors = darkColorScheme(
    primary = Mint,
    onPrimary = MintInk,
    primaryContainer = Color(0xFF12512C),
    onPrimaryContainer = Color(0xFFD5FFD9),
    secondary = Color(0xFFC1C7DC),
    onSecondary = Color(0xFF2B3040),
    tertiary = Coral,
    onTertiary = Color(0xFF690005),
    background = Ink,
    onBackground = Color(0xFFE4E2E9),
    surface = Surface,
    onSurface = Color(0xFFE4E2E9),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFC6C6D0),
    error = Coral,
    outline = Color(0xFF8F909A),
)

@Composable
fun MosaicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MosaicColors,
        content = content,
    )
}
