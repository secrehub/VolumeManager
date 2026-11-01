package moe.chensi.volume.engine

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory log shown on the diagnostics screen, so users can copy it
 * and send it back without needing `adb logcat`.
 */
object DiagLog {
    private const val TAG = "VolumeManager.Diag"
    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Incremented on every new line, so Compose can observe changes. */
    val version = mutableIntStateOf(0)

    fun i(message: String) = add("I", message, null)

    fun w(message: String, error: Throwable? = null) = add("W", message, error)

    fun e(message: String, error: Throwable? = null) = add("E", message, error)

    private fun add(level: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, message, error)
            "W" -> Log.w(TAG, message, error)
            else -> Log.i(TAG, message)
        }

        val text = buildString {
            append(synchronized(timeFormat) { timeFormat.format(Date()) })
            append(' ').append(level).append(' ').append(message)
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message)
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    append(" <- ").append(cause.javaClass.simpleName).append(": ")
                        .append(cause.message)
                    cause = cause.cause
                    depth++
                }
            }
        }

        synchronized(lines) {
            lines.addLast(text)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }

        // Snapshot state writes are safe from any thread
        version.intValue++
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        version.intValue++
    }
}
