package com.poros.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class GuideEntry(val icon: ImageVector, val title: String, val place: String, val body: String)

private val GUIDE = listOf(
    GuideEntry(
        Icons.Default.Subtitles, "Subtitles", "Open a video → top bar → Subtitles icon",
        "Switch subtitles ON or OFF. When ON you can load an SRT / ASS / VTT file, pick an embedded track, " +
            "and customize size, colour, background and delay."
    ),
    GuideEntry(
        Icons.Default.AutoAwesome, "Presets", "Open a video → top bar → sparkle icon",
        "Presets are ready-made video looks (Cinema, HDR, Vivid, Warm, Cool, B&W, Vintage, Anime, Night Vision, " +
            "Sharpen, Soft, Glow, Film). They change the picture colour, brightness and contrast live while you watch - " +
            "your video file is never changed. Use the intensity slider and Fine tune, hold \"original\" to compare " +
            "with the normal picture, and Save your own preset."
    ),
    GuideEntry(
        Icons.Default.Audiotrack, "Audio track", "Top bar → music-note icon",
        "Switch between audio languages / tracks if the video has more than one."
    ),
    GuideEntry(
        Icons.Default.Equalizer, "Equalizer & boost", "Bottom tools row",
        "Audio boost and equalizer. Sound boost above 100% works in HW+ decoder mode."
    )
)

@Composable
private fun GuideList(titleColor: Color, textColor: Color, whereColor: Color) {
    GUIDE.forEachIndexed { i, g ->
        if (i > 0) Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(g.icon, contentDescription = null, tint = whereColor, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(g.title, color = titleColor, fontWeight = FontWeight.Bold)
                Text(g.place, color = whereColor, style = MaterialTheme.typography.labelMedium)
                Text(g.body, color = textColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Shown once after install (and from Settings → Quick guide): where Subtitles / Presets are and what they do. */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to PK'S Player") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GuideList(
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    whereColor = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

/** First time the player opens: dims the screen and explains the top-bar icons. Blocks gestures until dismissed. */
@Composable
fun PlayerCoach(onDone: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(
            indication = null,
            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        ) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp).heightIn(max = 330.dp)
                .background(Color(0xFF151515), RoundedCornerShape(16.dp)).padding(16.dp)
        ) {
            Text("Where is what?", color = Color.White, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                GuideList(
                    titleColor = Color.White,
                    textColor = Color(0xFFCCCCCC),
                    whereColor = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Got it") }
        }
    }
}
