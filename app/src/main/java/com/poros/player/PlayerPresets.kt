package com.poros.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Side panel (not a modal dialog) so the video stays fully visible while the look is changed.
 * Every change goes straight to [onChange] and is applied to the live video immediately.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PresetPanel(
    st: Settings,
    state: FxState,
    onChange: (FxState) -> Unit,
    onCompare: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeCustom by remember { mutableStateOf<String?>(null) }
    var tuneOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val customs = st.fxCustoms()

    Surface(
        // swallow touches so the gesture layer underneath (volume / seek) is not triggered
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
        color = Color(0xEB121212),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(14.dp)
        ) {
            // ---- header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Presets", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { onCompare(true); tryAwaitRelease(); onCompare(false) })
                        }
                        .background(Color(0x33FFFFFF), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Compare, null, tint = Color.White)
                    Text("  Hold: original", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- built-in presets ----
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FxPreset.values().forEach { p ->
                    FilterChip(
                        selected = state.preset == p,
                        onClick = {
                            activeCustom = null
                            onChange(
                                if (state.preset == p) state.copy(preset = null)
                                else state.copy(preset = p, intensity = 1f)
                            )
                        },
                        label = { Text(p.label) }
                    )
                }
            }

            // ---- intensity ----
            if (state.preset != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${state.preset.label} intensity: ${(state.intensity * 100).roundToInt()}%",
                    color = Color.White, style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = state.intensity,
                    onValueChange = { onChange(state.copy(intensity = it)) },
                    valueRange = 0f..1f
                )
            }

            // ---- custom presets ----
            Spacer(Modifier.height(6.dp))
            Text("My presets", color = Color.White, style = MaterialTheme.typography.labelLarge)
            if (customs.isEmpty()) {
                Text(
                    "Nothing saved yet. Pick a look, tune it, then tap Save.",
                    color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    customs.forEach { (name, cs) ->
                        FilterChip(
                            selected = activeCustom == name,
                            onClick = { activeCustom = name; onChange(cs) },
                            label = { Text(name) }
                        )
                    }
                }
            }

            // ---- fine tune ----
            TextButton(onClick = { tuneOpen = !tuneOpen }) {
                Text("Fine tune")
                Icon(if (tuneOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (tuneOpen) {
                TuneSlider("Brightness", state.brightness) { onChange(state.copy(brightness = it)) }
                TuneSlider("Contrast", state.contrast) { onChange(state.copy(contrast = it)) }
                TuneSlider("Saturation", state.saturation) { onChange(state.copy(saturation = it)) }
                TuneSlider("Warmth", state.warmth) { onChange(state.copy(warmth = it)) }
            }

            // ---- actions ----
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { saving = true }, enabled = !state.isIdentity) { Text("Save") }
                OutlinedButton(onClick = { onChange(FxState()); activeCustom = null }) { Text("Reset") }
                val ac = activeCustom
                if (ac != null) {
                    TextButton(onClick = { st.deleteFxCustom(ac); activeCustom = null }) { Text("Delete") }
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(activeCustom ?: "") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) },
                    singleLine = true, label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        st.saveFxCustom(name.trim(), state)
                        activeCustom = name.trim()
                        saving = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TuneSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(78.dp))
        Slider(
            value = value, onValueChange = onValue, valueRange = -1f..1f,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${(value * 100).roundToInt()}", color = Color.White,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp)
        )
    }
}
