package moe.chensi.volume.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chensi.volume.BuildConfig
import moe.chensi.volume.Manager
import moe.chensi.volume.engine.DiagLog
import moe.chensi.volume.engine.ShizukuShell
import kotlin.math.roundToInt

private fun systemProperty(name: String): String {
    return try {
        @Suppress("PrivateApi")
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }
}

fun buildDiagnosticsReport(manager: Manager): String = buildString {
    appendLine("=== Volume Manager diagnostik ===")
    appendLine("Versi aplikasi: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Build: ${Build.DISPLAY}")
    val colorOs = systemProperty("ro.build.version.opporom")
    if (colorOs.isNotEmpty()) appendLine("ColorOS: $colorOs")
    appendLine("Shizuku: status=${manager.shizukuStatus}, uid=${ShizukuShell.uid()}, versi=${ShizukuShell.version()}")
    appendLine("Mesin: ${manager.engineMode}")
    if (manager.engineMode == Manager.EngineMode.SessionEffect) {
        val engine = manager.sessionEngine
        appendLine("Scan terakhir: ${engine.lastScanSummary.value}")
        appendLine()
        appendLine("--- Sesi audio ---")
        val infos = engine.sessionInfos.value
        if (infos.isEmpty()) appendLine("(tidak ada)")
        for (s in infos) {
            appendLine(
                "${s.packageName} sesi=${s.sessionId} uid=${s.uid} via=${s.source} " +
                        "aktif=${s.active} efek=${s.effect} kontrol=${s.hasControl} " +
                        "volume=${if (s.appliedVolume.isNaN()) "-" else "${(s.appliedVolume * 100).roundToInt()}%"}" +
                        (s.error?.let { " ERROR=$it" } ?: "")
            )
        }
        appendLine()
        appendLine("--- Cuplikan dumpsys ---")
        appendLine(engine.lastRawExcerpt.value)
    }
    appendLine()
    appendLine("--- Log ---")
    DiagLog.snapshot().forEach { appendLine(it) }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Volume Manager diagnostik", text))
    Toast.makeText(context, "Log disalin ke clipboard", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Volume Manager diagnostik")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(intent, "Bagikan log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsDialog(manager: Manager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    // Read to recompose when the log changes
    @Suppress("UNUSED_VARIABLE")
    val logVersion = DiagLog.version.intValue
    val logLines = remember(logVersion) { DiagLog.snapshot().takeLast(200).reversed() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Diagnostik", style = MaterialTheme.typography.headlineSmall)

            InfoCard {
                Line("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
                Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                Line("Shizuku", "${manager.shizukuStatus}, uid ${ShizukuShell.uid()}")
                Line(
                    "Mesin", when (manager.engineMode) {
                        Manager.EngineMode.PlayerVolume -> "IPlayer (Android 13+/root)"
                        Manager.EngineMode.SessionEffect -> "Efek per sesi (Android 10–12)"
                        Manager.EngineMode.Unknown -> "Belum aktif"
                    }
                )
                if (manager.engineMode == Manager.EngineMode.SessionEffect) {
                    Line("Scan", manager.sessionEngine.lastScanSummary.value)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                if (ShizukuShell.isAvailable()) {
                                    val id = ShizukuShell.run("id", 3000)
                                    DiagLog.i("id: ${id.stdout.trim()} ${id.stderr.trim()}")
                                    val list = ShizukuShell.run("dumpsys -l", 5000)
                                    val audio = list.stdout.lines().map { it.trim() }
                                        .filter { it.contains("audio", ignoreCase = true) }
                                    DiagLog.i("Layanan audio: ${audio.joinToString(", ").ifEmpty { "(tidak ada, exit=${list.exitCode})" }}")
                                } else {
                                    DiagLog.w("Shizuku tidak tersedia untuk tes")
                                }
                                if (manager.engineMode == Manager.EngineMode.SessionEffect) {
                                    val summary = manager.sessionEngine.scanBlocking()
                                    DiagLog.i("Scan manual: $summary")
                                }
                            } catch (e: Throwable) {
                                DiagLog.e("Tes gagal", e)
                            }
                        }
                        running = false
                    }
                }) {
                    Text(if (running) "Menjalankan…" else "Jalankan tes")
                }
                OutlinedButton(onClick = { copyToClipboard(context, buildDiagnosticsReport(manager)) }) {
                    Text("Salin log")
                }
                OutlinedButton(onClick = { share(context, buildDiagnosticsReport(manager)) }) {
                    Text("Bagikan")
                }
                OutlinedButton(onClick = { DiagLog.clear() }) {
                    Text("Hapus log")
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Tutup")
                }
            }

            if (manager.engineMode == Manager.EngineMode.SessionEffect) {
                Text("Sesi audio", style = MaterialTheme.typography.titleMedium)
                val infos = manager.sessionEngine.sessionInfos.value
                if (infos.isEmpty()) {
                    Text(
                        "Belum ada sesi. Putar sesuatu (misalnya YouTube), lalu tekan \"Jalankan tes\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                for (s in infos) {
                    InfoCard {
                        Text(
                            "${manager.apps[s.packageName]?.name ?: s.packageName}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Line("Sesi", "${s.sessionId} (${if (s.active) "aktif" else "diam"}, via ${s.source})")
                        Line("Efek", "${s.effect}${if (s.hasControl) "" else " — tanpa kontrol"}")
                        Line(
                            "Volume",
                            if (s.appliedVolume.isNaN()) "-" else "${(s.appliedVolume * 100).roundToInt()}%"
                        )
                        s.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }

                Text("Cuplikan dumpsys", style = MaterialTheme.typography.titleMedium)
                Mono(manager.sessionEngine.lastRawExcerpt.value.ifEmpty { "(belum ada)" })
            }

            Text("Log (terbaru di atas)", style = MaterialTheme.typography.titleMedium)
            Mono(logLines.joinToString("\n").ifEmpty { "(kosong)" })
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Mono(text: String) {
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(8.dp)
        )
    }
}
