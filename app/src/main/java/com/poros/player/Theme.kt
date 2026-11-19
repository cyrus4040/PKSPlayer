package com.poros.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val label: String,
    val primary: Color,
    val onPrimary: Color,
    val bg: Color,
    val surface: Color
) {
    LIME("Lime", Color(0xFFB4FF1A), Color.Black, Color(0xFF0A0A0A), Color(0xFF151515)),
    AMOLED("AMOLED", Color(0xFFFFFFFF), Color.Black, Color(0xFF000000), Color(0xFF0A0A0A)),
    NEON("Neon", Color(0xFFFF2BD6), Color.White, Color(0xFF0B0014), Color(0xFF180027)),
    BLUE("Blue", Color(0xFF4DA3FF), Color.Black, Color(0xFF060E1A), Color(0xFF0E1B2E)),
    PURPLE("Purple", Color(0xFFB388FF), Color.Black, Color(0xFF0F0818), Color(0xFF1B1030)),
    GREEN("Green", Color(0xFF00E676), Color.Black, Color(0xFF04120A), Color(0xFF0B2014))
}

@Composable
fun PkTheme(themeName: String, content: @Composable () -> Unit) {
    val t = AppTheme.values().firstOrNull { it.name == themeName } ?: AppTheme.LIME
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.primary.copy(alpha = 0.25f),
            onPrimaryContainer = Color.White,
            secondary = t.primary,
            onSecondary = t.onPrimary,
            secondaryContainer = t.primary.copy(alpha = 0.22f),
            onSecondaryContainer = Color.White,
            background = t.bg,
            onBackground = Color.White,
            surface = t.surface,
            onSurface = Color.White,
            surfaceVariant = t.surface,
            onSurfaceVariant = Color(0xFFB8B8B8),
            surfaceTint = Color.Transparent
        ),
        content = content
    )
}
