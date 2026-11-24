package com.poros.player

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

@Composable
fun SpeedDialog(current: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed: ${String.format(Locale.US, "%.2f", current)}x") },
        text = {
            Column {
                Slider(value = current, onValueChange = { onSet((it * 20).toInt() / 20f) },
                    valueRange = 0.25f..4f)
                presets.chunked(3).forEach { row ->
                    Row {
                        row.forEach { p ->
                            FilterChip(selected = current == p, onClick = { onSet(p) },
                                label = { Text("${p}x") }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

fun trackLabel(f: Format, idx: Int): String {
    val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }
    val base = f.label ?: lang ?: "Track ${idx + 1}"
    val extra = mutableListOf<String>()
    if (f.channelCount > 0 && f.sampleMimeType?.startsWith("audio") == true) extra.add("${f.channelCount}ch")
    f.sampleMimeType?.substringAfter('/')?.let { extra.add(it) }
    return if (extra.isEmpty()) base else base + " • " + extra.joinToString(" ")
}

@Composable
fun TracksDialog(player: Player, trackType: Int, title: String, allowOff: Boolean, onDismiss: () -> Unit) {
    var tracks by remember { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val groups = tracks.groups.filter { it.type == trackType }
    val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(trackType)

    fun select(g: Tracks.Group, i: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (groups.isEmpty()) Text("No tracks available")
                if (allowOff) Row(
                    Modifier.fillMaxWidth().clickable {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(trackType, true).build()
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = disabled, onClick = null)
                    Spacer(Modifier.width(8.dp)); Text("Off")
                }
                groups.forEach { g ->
                    for (i in 0 until g.length) {
                        val sel = g.isTrackSelected(i) && !disabled
                        Row(
                            Modifier.fillMaxWidth().clickable { select(g, i) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = sel, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(trackLabel(g.getTrackFormat(i), i))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun SubtitleDialog(
    act: PlayerActivity, st: Settings,
    onPick: () -> Unit, onTracks: () -> Unit, onDismiss: () -> Unit
) {
    var delay by remember { mutableLongStateOf(act.subDelay) }
    var on by remember {
        mutableStateOf(!act.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "Subtitles: ON" else "Subtitles: OFF", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Switch(checked = on, onCheckedChange = { v ->
                        on = v
                        st.set("subon", v)
                        act.player.trackSelectionParameters = act.player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !v).build()
                    })
                }
                if (!on) {
                    Text("Turn subtitles on to load a file and customize size, colour, background and delay.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                Spacer(Modifier.height(4.dp))
                Text(act.subName ?: "No external subtitle loaded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onPick) { Text("Load file (SRT / ASS / VTT)") }
                }
                TextButton(onClick = onTracks) { Text("Choose subtitle track") }

                Spacer(Modifier.height(8.dp))
                Text("Delay: ${delay}ms", style = MaterialTheme.typography.labelLarge)
                Row {
                    listOf(-500L, -100L, 100L, 500L).forEach { d ->
                        OutlinedButton(onClick = { delay += d }, modifier = Modifier.padding(end = 4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (d > 0) "+$d" else "$d")
                        }
                    }
                }
                Row {
                    TextButton(onClick = { delay = 0 }) { Text("Reset") }
                    TextButton(
                        enabled = act.subUri != null && delay != act.subDelay,
                        onClick = { act.subUri?.let { act.loadSubtitle(it, act.subName ?: "sub.srt", delay) } }
                    ) { Text("Apply delay") }
                }

                Spacer(Modifier.height(8.dp))
                Text("Size: ${st.float("subsize", 22f).toInt()}sp", style = MaterialTheme.typography.labelLarge)
                Slider(value = st.float("subsize", 22f), onValueChange = { st.set("subsize", it) }, valueRange = 14f..40f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SUB_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.padding(end = 10.dp).size(30.dp).clip(CircleShape).background(c)
                                .border(if (st.int("subcolor", 0) == i) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { st.set("subcolor", i) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background", Modifier.weight(1f))
                    Switch(checked = st.bool("subbg", false), onCheckedChange = { st.set("subbg", it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun AudioFxDialog(fx: AudioFx, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio boost & equalizer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Audio boost: ${fx.boost}%", style = MaterialTheme.typography.labelLarge)
                Slider(value = fx.boost.toFloat(), onValueChange = { fx.updateBoost(it.toInt()) }, valueRange = 0f..100f)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Equalizer", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = fx.eqOn, onCheckedChange = { fx.updateEqOn(it) }, enabled = fx.available)
                }
                if (!fx.available) Text("Equalizer not available on this device/session",
                    style = MaterialTheme.typography.bodySmall)
                fx.levels.forEachIndexed { i, level ->
                    Text(fx.freqs.getOrElse(i) { "" } + "  ${level / 100} dB", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { fx.setLevel(i, it.toInt()) },
                        valueRange = fx.minLevel.toFloat()..fx.maxLevel.toFloat()
                    )
                }
                if (fx.available) TextButton(onClick = { fx.resetEq() }) { Text("Reset EQ") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun SleepDialog(current: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current > 0) "Sleep timer: $current min" else "Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    TextButton(onClick = { onSet(0); onSet(m); onDismiss() }) { Text("$m minutes") }
                }
                TextButton(onClick = { onSet(0); onDismiss() }) { Text("Turn off") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun DecoderDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val opts = listOf(
        "HW" to "Recommended • best quality, fast & battery friendly (volume up to 100%)",
        "HW+" to "Hardware + software fallback • sound boost, volume up to 200%",
        "SW" to "Software decoder preferred • best compatibility (volume up to 100%)"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decoder") },
        text = {
            Column {
                opts.forEachIndexed { i, (n, d) ->
                    Row(Modifier.fillMaxWidth().clickable { if (i != current) onPick(i) else onDismiss() }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == i, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column { Text(n); Text(d, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("Changing the decoder restarts playback at the current position.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun BookmarksDialog(player: Player, st: Settings, onDismiss: () -> Unit) {
    val id = player.currentMediaItem?.mediaId ?: ""
    val list = st.bookmarks(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("No bookmarks yet. Tap the bookmark+ icon while playing.")
                list.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { player.seekTo(p); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(p), Modifier.weight(1f))
                        IconButton(onClick = { st.removeBookmark(id, p) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
