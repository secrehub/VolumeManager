package moe.chensi.volume.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User options for the volume popup. Compose-observable, persisted in SharedPreferences. */
class PopupSettings private constructor(private val prefs: SharedPreferences) {
    companion object {
        @Volatile
        private var instance: PopupSettings? = null

        fun get(context: Context): PopupSettings {
            return instance ?: synchronized(this) {
                instance ?: PopupSettings(
                    context.applicationContext.getSharedPreferences("popup", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        const val STREAM_MEDIA = "media"
        const val STREAM_RING = "ring"
        const val STREAM_NOTIFICATION = "notification"
        const val STREAM_ALARM = "alarm"
        const val STREAM_CALL = "call"

        val ALL_STREAMS = listOf(STREAM_MEDIA, STREAM_RING, STREAM_NOTIFICATION, STREAM_ALARM, STREAM_CALL)
    }

    enum class Style { Compact, Classic }
    enum class Position { Right, Left, Center }
    enum class Size { Small, Medium, Large }
    enum class BarStyle { Thin, Bold }

    var style by mutableStateOf(enumPref("style", Style.Compact))
        private set
    var position by mutableStateOf(enumPref("position", Position.Right))
        private set
    var size by mutableStateOf(enumPref("size", Size.Medium))
        private set
    var barStyle by mutableStateOf(enumPref("bar_style", BarStyle.Thin))
        private set
    var showApps by mutableStateOf(prefs.getBoolean("show_apps", true))
        private set
    var streams by mutableStateOf(
        prefs.getStringSet("streams", null)?.toSet()
            ?: setOf(STREAM_MEDIA, STREAM_RING, STREAM_NOTIFICATION, STREAM_ALARM)
    )
        private set
    var autoHideSeconds by mutableIntStateOf(prefs.getInt("auto_hide", 4))
        private set
    var opacity by mutableFloatStateOf(prefs.getFloat("opacity", 0.92f))
        private set

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    fun updateStyle(value: Style) {
        style = value; prefs.edit().putString("style", value.name).apply()
    }

    fun updatePosition(value: Position) {
        position = value; prefs.edit().putString("position", value.name).apply()
    }

    fun updateSize(value: Size) {
        size = value; prefs.edit().putString("size", value.name).apply()
    }

    fun updateBarStyle(value: BarStyle) {
        barStyle = value; prefs.edit().putString("bar_style", value.name).apply()
    }

    fun updateShowApps(value: Boolean) {
        showApps = value; prefs.edit().putBoolean("show_apps", value).apply()
    }

    fun toggleStream(id: String, visible: Boolean) {
        streams = if (visible) streams + id else streams - id
        prefs.edit().putStringSet("streams", streams).apply()
    }

    fun updateAutoHideSeconds(value: Int) {
        autoHideSeconds = value.coerceIn(2, 15); prefs.edit().putInt("auto_hide", autoHideSeconds).apply()
    }

    fun updateOpacity(value: Float) {
        opacity = value.coerceIn(0.5f, 1f); prefs.edit().putFloat("opacity", opacity).apply()
    }
}
