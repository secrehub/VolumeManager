package moe.chensi.volume.compose

import android.media.AudioManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chensi.volume.Manager
import moe.chensi.volume.R
import moe.chensi.volume.data.App
import moe.chensi.volume.data.PopupSettings
import moe.chensi.volume.engine.VolumeLimits
import moe.chensi.volume.system.SystemVolume
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFFFFF)
private val BoostAccent = Color(0xFFFFB74D)

/**
 * A vertical volume bar in the style of the ColorOS volume panel.
 * [fraction] is 0..1. [markerFraction] draws a small tick (used for the 100% mark when boosting).
 */
@Composable
fun VerticalVolumeBar(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    height: Dp,
    thin: Boolean,
    fillColor: Color = Accent,
    markerFraction: Float? = null,
    onInteract: () -> Unit = {},
    bottom: @Composable () -> Unit
) {
    val latestOnChange by rememberUpdatedState(onFractionChange)
    val latestOnInteract by rememberUpdatedState(onInteract)
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    val trackColor = Color.White.copy(alpha = 0.22f)
    val barWidth = if (thin) 5.dp else 34.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(height)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        latestOnInteract()
                        latestOnChange((1f - offset.y / size.height).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { latestOnInteract() }
                    ) { change, _ ->
                        change.consume()
                        latestOnInteract()
                        latestOnChange((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .width(barWidth)
                    .height(height)
            ) {
                val radius = CornerRadius(size.width / 2, size.width / 2)
                drawRoundRect(color = trackColor, cornerRadius = radius)
                val fillHeight = size.height * animated
                if (fillHeight > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(0f, size.height - fillHeight),
                        size = Size(size.width, fillHeight),
                        cornerRadius = radius
                    )
                }
                if (markerFraction != null && markerFraction in 0f..1f) {
                    val y = size.height * (1f - markerFraction)
                    drawRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        topLeft = Offset(0f, y - 1.dp.toPx()),
                        size = Size(size.width, 2.dp.toPx())
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            bottom()
        }
    }
}

private data class StreamDef(val id: String, val stream: Int, val icon: ImageVector, val nameRes: Int)

private val streamDefs = listOf(
    StreamDef(PopupSettings.STREAM_MEDIA, AudioManager.STREAM_MUSIC, Icons.Default.MusicNote, R.string.stream_media),
    StreamDef(PopupSettings.STREAM_RING, AudioManager.STREAM_RING, Icons.Default.Notifications, R.string.stream_ring),
    StreamDef(PopupSettings.STREAM_NOTIFICATION, AudioManager.STREAM_NOTIFICATION, Icons.AutoMirrored.Filled.Message, R.string.stream_notification),
    StreamDef(PopupSettings.STREAM_ALARM, AudioManager.STREAM_ALARM, Icons.Default.Alarm, R.string.stream_alarm),
    StreamDef(PopupSettings.STREAM_CALL, AudioManager.STREAM_VOICE_CALL, Icons.Default.Call, R.string.stream_call),
)

/** Compact, vertical-bar volume popup: per-app bars first, then system streams. */
@Composable
fun CompactVolumePopup(
    manager: Manager,
    settings: PopupSettings,
    onInteract: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = manager.audioManager
    var label by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        VolumeChangeObserver.startObserving(context)
        onDispose { VolumeChangeObserver.stopObserving() }
    }
    val volumeChangedCount = VolumeChangeObserver.volumeChangedCount

    val barHeight = when (settings.size) {
        PopupSettings.Size.Small -> 130.dp
        PopupSettings.Size.Medium -> 170.dp
        PopupSettings.Size.Large -> 220.dp
    }
    val thin = settings.barStyle == PopupSettings.BarStyle.Thin
    val activeApps = if (settings.showApps) {
        manager.apps.values.filter { it.isPlayer && it.isPlaying && !it.hidden }
            .sortedWith(App.comparator)
    } else {
        emptyList()
    }
    val streamName = streamDefs.associate { it.id to stringResource(it.nameRes) }

    Surface(
        color = Color(0xFF1C1C1E).copy(alpha = settings.opacity),
        contentColor = Color.White,
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label ?: streamName[PopupSettings.STREAM_MEDIA] ?: "",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f, fill = false)
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Pengaturan",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (app in activeApps) {
                    val max = VolumeLimits.max
                    VerticalVolumeBar(
                        fraction = app.volume / max,
                        onFractionChange = { f ->
                            val value = (f * max * 100).roundToInt() / 100f
                            app.volume = value
                            label = "${app.name} · ${(value * 100).roundToInt()}%"
                        },
                        height = barHeight,
                        thin = thin,
                        fillColor = if (app.volume > 1f) BoostAccent else Accent,
                        markerFraction = if (max > 1f) 1f / max else null,
                        onInteract = {
                            label = "${app.name} · ${(app.volume * 100).roundToInt()}%"
                            onInteract()
                        }
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = app.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color.Gray)
                            )
                        }
                    }
                }

                if (activeApps.isNotEmpty() && settings.streams.isNotEmpty()) {
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(barHeight)
                            .background(Color.White.copy(alpha = 0.15f))
                    )
                }

                for (def in streamDefs) {
                    if (def.id !in settings.streams) continue
                    StreamBar(
                        def = def,
                        name = streamName[def.id] ?: def.id,
                        audioManager = audioManager,
                        volumeChangedCount = volumeChangedCount,
                        height = barHeight,
                        thin = thin,
                        onLabel = { label = it },
                        onInteract = onInteract
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamBar(
    def: StreamDef,
    name: String,
    audioManager: AudioManager,
    volumeChangedCount: Int,
    height: Dp,
    thin: Boolean,
    onLabel: (String) -> Unit,
    onInteract: () -> Unit
) {
    val context = LocalContext.current
    val max = remember(def.stream) { audioManager.getStreamMaxVolume(def.stream).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audioManager.getStreamVolume(def.stream)) }

    LaunchedEffect(volumeChangedCount) {
        volume = audioManager.getStreamVolume(def.stream)
    }

    VerticalVolumeBar(
        fraction = volume.toFloat() / max,
        onFractionChange = { f ->
            val target = (f * max).roundToInt()
            if (target != volume) {
                volume = if (SystemVolume.setStreamVolume(context, audioManager, def.stream, target)) {
                    target
                } else {
                    audioManager.getStreamVolume(def.stream)
                }
                VolumeChangeObserver.notifyVolumeChanged()
            }
            onLabel("$name · $volume/$max")
        },
        height = height,
        thin = thin,
        onInteract = {
            onLabel("$name · $volume/$max")
            onInteract()
        }
    ) {
        Icon(def.icon, contentDescription = name, modifier = Modifier.size(24.dp))
    }
}
