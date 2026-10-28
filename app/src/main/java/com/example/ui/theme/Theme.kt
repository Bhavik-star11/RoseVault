package com.example.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

val ObsidianPlum = Color(0xFF3B0A22)
val MidnightVelvet = Color(0xFF561233)
val GlassSurface = Color(0xFF701943)
val GlassBorder = Color(0x66FFB2D1)
val GlassHighlight = Color(0x3DFF80AB)
val NeonRose = Color(0xFFFF4D94)
val BlushPink = Color(0xFFFFC1DC)
val CrimsonGold = Color(0xFFF06292)
val SoftRoseText = Color(0xFFFFF5F8)
val MutedRoseGray = Color(0xFFF8BBD0)
val EmeraldEncrypted = Color(0xFF49F2A1)
val AmberWarning = Color(0xFFFFCA4B)
val DangerRed = Color(0xFFFF4071)

@Composable
fun RoseVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = NeonRose,
                onPrimary = SoftRoseText,
                secondary = BlushPink,
                background = ObsidianPlum,
                surface = MidnightVelvet,
                surfaceVariant = GlassSurface,
                onSurface = SoftRoseText,
                onBackground = SoftRoseText,
                onSurfaceVariant = MutedRoseGray,
                outline = GlassBorder,
                error = DangerRed,
            ),
        content = {
            CompositionLocalProvider(LocalContentColor provides SoftRoseText) { content() }
        },
    )
}
