package moe.chensi.volume.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chensi.volume.R
import moe.chensi.volume.data.PopupSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PopupSettingsDialog(
    settings: PopupSettings,
    onTestPopup: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Pengaturan popup", style = MaterialTheme.typography.headlineSmall)

            Section("Gaya") {
                Choices(
                    listOf(
                        PopupSettings.Style.Compact to "Ringkas (vertikal)",
                        PopupSettings.Style.Classic to "Klasik (daftar)"
                    ), settings.style, settings::updateStyle
                )
            }

            Section("Posisi") {
                Choices(
                    listOf(
                        PopupSettings.Position.Right to "Kanan",
                        PopupSettings.Position.Left to "Kiri",
                        PopupSettings.Position.Center to "Tengah"
                    ), settings.position, settings::updatePosition
                )
            }

            Section("Ukuran") {
                Choices(
                    listOf(
                        PopupSettings.Size.Small to "Kecil",
                        PopupSettings.Size.Medium to "Sedang",
                        PopupSettings.Size.Large to "Besar"
                    ), settings.size, settings::updateSize
                )
            }

            Section("Bentuk slider") {
                Choices(
                    listOf(
                        PopupSettings.BarStyle.Thin to "Tipis",
                        PopupSettings.BarStyle.Bold to "Tebal"
                    ), settings.barStyle, settings::updateBarStyle
                )
            }

            Section("Isi popup") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Volume per aplikasi yang sedang bunyi", modifier = Modifier.weight(1f))
                    Switch(checked = settings.showApps, onCheckedChange = settings::updateShowApps)
                }
                val names = mapOf(
                    PopupSettings.STREAM_MEDIA to stringResource(R.string.stream_media),
                    PopupSettings.STREAM_RING to stringResource(R.string.stream_ring),
                    PopupSettings.STREAM_NOTIFICATION to stringResource(R.string.stream_notification),
                    PopupSettings.STREAM_ALARM to stringResource(R.string.stream_alarm),
                    PopupSettings.STREAM_CALL to stringResource(R.string.stream_call),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in PopupSettings.ALL_STREAMS) {
                        val selected = id in settings.streams
                        FilterChip(
                            selected = selected,
                            onClick = { settings.toggleStream(id, !selected) },
                            label = { Text(names[id] ?: id) }
                        )
                    }
                }
            }

            Section("Sembunyi otomatis: ${settings.autoHideSeconds} detik") {
                Slider(
                    value = settings.autoHideSeconds.toFloat(),
                    onValueChange = { settings.updateAutoHideSeconds(it.roundToInt()) },
                    valueRange = 2f..15f,
                    steps = 12
                )
            }

            Section("Kegelapan latar: ${(settings.opacity * 100).roundToInt()}%") {
                Slider(
                    value = settings.opacity,
                    onValueChange = settings::updateOpacity,
                    valueRange = 0.5f..1f
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTestPopup) { Text("Coba popup") }
                OutlinedButton(onClick = onDismiss) { Text("Selesai") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, text) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(text) }
            )
        }
    }
}
