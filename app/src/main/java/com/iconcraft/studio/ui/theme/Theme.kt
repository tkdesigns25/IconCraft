package com.iconcraft.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBackground = Color(0xFF0A0D14)
val SlateSurface = Color(0xFF131824)
val SlateSurfaceVariant = Color(0xFF1C2333)
val IndigoPrimary = Color(0xFF6366F1)
val VioletSecondary = Color(0xFFA855F7)
val EmeraldAccent = Color(0xFF10B981)
val CoralDanger = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

private val IconCraftDarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF282B63),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = VioletSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B1F5E),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = EmeraldAccent,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = CoralDanger
)

@Composable
fun IconCraftTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IconCraftDarkColorScheme,
        content = content
    )
}
