package moe.chensi.volume.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import moe.chensi.volume.engine.DiagLog
import moe.chensi.volume.engine.ShizukuShell

/**
 * Changing ring/notification volume to or from 0 toggles silent mode, which Android treats as
 * a Do Not Disturb change and refuses without "Do Not Disturb access". That used to crash the
 * app. These helpers grant the access through Shizuku when possible and never throw.
 */
object SystemVolume {
    @Volatile
    private var grantAttempted = false

    fun hasDndAccess(context: Context): Boolean {
        return try {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true
        } catch (_: Throwable) {
            false
        }
    }

    /** Grant Do Not Disturb access to this app via Shizuku. Blocking; call off the main thread when possible. */
    fun ensureDndAccess(context: Context): Boolean {
        if (hasDndAccess(context)) return true
        if (!ShizukuShell.isAvailable()) return false
        return try {
            val result = ShizukuShell.run("cmd notification allow_dnd ${context.packageName}", 5000)
            grantAttempted = true
            val granted = hasDndAccess(context)
            DiagLog.i(
                "Izin Jangan Ganggu: ${if (granted) "diberikan" else "gagal"} " +
                        "(exit=${result.exitCode}${if (result.stderr.isNotBlank()) ", ${result.stderr.trim()}" else ""})"
            )
            granted
        } catch (e: Throwable) {
            DiagLog.w("Gagal memberi izin Jangan Ganggu", e)
            false
        }
    }

    fun setStreamVolume(context: Context, audioManager: AudioManager, stream: Int, index: Int): Boolean {
        try {
            audioManager.setStreamVolume(stream, index, 0)
            return true
        } catch (e: SecurityException) {
            DiagLog.w("setStreamVolume($stream, $index) ditolak", e)
        } catch (e: Throwable) {
            DiagLog.e("setStreamVolume($stream, $index) gagal", e)
            return false
        }

        if (!grantAttempted && ensureDndAccess(context)) {
            try {
                audioManager.setStreamVolume(stream, index, 0)
                return true
            } catch (e: Throwable) {
                DiagLog.w("setStreamVolume tetap ditolak setelah izin", e)
            }
        }

        toast(context, "Perlu izin \"Jangan Ganggu\" untuk mengubah volume ini ke/dari 0")
        return false
    }

    fun setRingerMode(context: Context, audioManager: AudioManager, mode: Int): Boolean {
        try {
            audioManager.ringerMode = mode
            return true
        } catch (e: SecurityException) {
            DiagLog.w("setRingerMode($mode) ditolak", e)
        } catch (e: Throwable) {
            DiagLog.e("setRingerMode($mode) gagal", e)
            return false
        }

        if (!grantAttempted && ensureDndAccess(context)) {
            try {
                audioManager.ringerMode = mode
                return true
            } catch (e: Throwable) {
                DiagLog.w("setRingerMode tetap ditolak setelah izin", e)
            }
        }

        toast(context, "Perlu izin \"Jangan Ganggu\" untuk mengubah mode dering")
        return false
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }
}
