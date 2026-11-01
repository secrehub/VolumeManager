package moe.chensi.volume.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Per-app volume for Android 10-12 without root.
 *
 * On these versions the `shell` user (Shizuku) is not allowed to get the
 * `IPlayer` handles of other apps' players, so the original method can't work.
 * Instead we:
 *  1. read `dumpsys media.audio_policy` (or `media.audio_flinger`) through Shizuku to learn
 *     which audio session belongs to which app,
 *  2. attach a [DynamicsProcessing] effect to every session and use its input gain as the
 *     app's volume. This adds no latency and also allows boosting above 100%.
 */
class SessionVolumeEngine(private val context: Context, private val host: Host) {
    interface Host {
        /** Map a process id to its package name (used for the audio_flinger fallback). */
        fun packageForPid(pid: Int): String?

        /** Pick the package that should own [uid] (shared uids can have several). */
        fun packageForUid(uid: Int, candidates: Array<String>): String?

        /** Current volume (0..[MAX_VOLUME]) of an app, 1 = unchanged. */
        fun volumeOf(packageName: String): Float

        /** Called on the main thread after every scan. key = package, value = playing now. */
        fun onScanResult(packages: Map<String, Boolean>)
    }

    companion object {
        const val MAX_VOLUME = 3f

        private const val EFFECT_PRIORITY = 1000
        private const val SESSION_EXPIRE_MS = 20_000L
        private const val EFFECT_RETRY_MS = 30_000L
        private const val ACTIVE_POLL_MS = 4_000L
        private const val IDLE_POLL_MS = 15_000L

        fun volumeToDb(volume: Float): Float {
            if (volume <= 0.001f) return -96f
            return (20 * log10(volume.toDouble())).toFloat()
        }
    }

    data class SessionInfo(
        val sessionId: Int,
        val packageName: String,
        val uid: Int,
        val source: String,
        val active: Boolean,
        val effect: String,
        val hasControl: Boolean,
        val appliedVolume: Float,
        val error: String?
    )

    private class SessionEffect(
        val sessionId: Int,
        var packageName: String,
        var uid: Int,
        var source: String
    ) {
        var dynamics: DynamicsProcessing? = null
        var equalizer: Equalizer? = null
        var eqRange: ShortArray? = null
        var lastSeen = 0L
        var active = false
        var appliedVolume = Float.NaN
        var error: String? = null
        /** Time of the last failed effect creation, 0 = never failed */
        var createFailedAt = 0L

        val effect: AudioEffect?
            get() = dynamics ?: equalizer

        fun release() {
            try {
                dynamics?.release()
            } catch (_: Throwable) {
            }
            try {
                equalizer?.release()
            } catch (_: Throwable) {
            }
            dynamics = null
            equalizer = null
        }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-volume-engine")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)!!

    /** Accessed only on [worker] */
    private val sessions = HashMap<Int, SessionEffect>()

    @Volatile
    private var started = false
    private var pendingScan: ScheduledFuture<*>? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var lastPolicyFailure: String? = null
    private var lastActiveCount = 0

    /** Compose-observable snapshot for the diagnostics screen. */
    val sessionInfos = mutableStateOf<List<SessionInfo>>(emptyList())
    val lastScanSummary = mutableStateOf("Belum ada scan")
    val lastRawExcerpt = mutableStateOf("")

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // Configs are anonymized for us, but still tell us *when* something changed.
            scheduleScan(250)
            scheduleScan(1500, replace = false)
        }
    }

    private val effectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
            if (session <= 0 || pkg.isNullOrEmpty() || pkg == context.packageName) return

            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    DiagLog.i("Broadcast: $pkg membuka sesi $session")
                    worker.execute {
                        val uid = try {
                            context.packageManager.getApplicationInfo(pkg, 0).uid
                        } catch (_: Throwable) {
                            -1
                        }
                        track(session, pkg, uid, "broadcast", active = true, now = System.currentTimeMillis())
                        publish()
                    }
                }

                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    DiagLog.i("Broadcast: $pkg menutup sesi $session")
                    worker.execute {
                        sessions.remove(session)?.release()
                        publish()
                    }
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        DiagLog.i("Mesin sesi (DynamicsProcessing) dimulai")

        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        } catch (e: Throwable) {
            DiagLog.w("Tidak bisa mendaftar playback callback", e)
        }

        try {
            ContextCompat.registerReceiver(
                context, effectSessionReceiver,
                IntentFilter().apply {
                    addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Throwable) {
            DiagLog.w("Tidak bisa mendaftar receiver sesi efek", e)
        }

        scheduleScan(0)
        schedulePoll()
    }

    private fun schedulePoll() {
        pollTask?.cancel(false)
        val delay = if (lastActiveCount > 0) ACTIVE_POLL_MS else IDLE_POLL_MS
        pollTask = worker.schedule({
            scanNow()
            schedulePoll()
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun scheduleScan(delayMs: Long, replace: Boolean = true) {
        if (!started) return
        if (replace) pendingScan?.cancel(false)
        val future = worker.schedule({ scanNow() }, delayMs, TimeUnit.MILLISECONDS)
        if (replace) pendingScan = future
    }

    /** Re-apply the volume of [packageName] to all of its sessions. */
    fun applyVolume(packageName: String) {
        if (!started) return
        worker.execute {
            var changed = false
            for (s in sessions.values) {
                if (s.packageName == packageName) {
                    applyTo(s)
                    changed = true
                }
            }
            if (changed) publish()
        }
    }

    /** Runs a scan and waits for it, returning the summary. For the diagnostics screen. */
    fun scanBlocking(): String {
        return try {
            worker.submit<String> {
                scanNow()
                lastScanSummary.value
            }.get(20, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            DiagLog.e("Scan manual gagal", e)
            "Gagal: ${e.message}"
        }
    }

    // ---- worker thread only below ----

    private fun scanNow() {
        if (!ShizukuShell.isAvailable()) {
            if (lastPolicyFailure != "shizuku") {
                DiagLog.w("Scan dilewati: Shizuku tidak tersedia / belum diizinkan")
                lastPolicyFailure = "shizuku"
            }
            lastScanSummary.value = "Shizuku tidak tersedia"
            return
        }

        val now = System.currentTimeMillis()
        var source = "audio_policy"
        var found = 0

        try {
            val result = ShizukuShell.run("dumpsys media.audio_policy")
            val clients = AudioDumpParser.parsePolicyClients(result.stdout)
            excerpt(result, "Session Id")

            if (clients.isEmpty()) {
                val reason = "audio_policy: ${result.stdout.length} karakter, exit=${result.exitCode}" +
                        (if (result.timedOut) ", timeout" else "") +
                        (if (result.stderr.isNotBlank()) ", err=${result.stderr.take(200)}" else "")
                if (lastPolicyFailure != reason) {
                    DiagLog.w("Tidak ada klien di $reason, coba audio_flinger")
                    lastPolicyFailure = reason
                }
                source = "audio_flinger"
                found = scanFlinger(now)
            } else {
                lastPolicyFailure = null
                val myUid = Process.myUid()
                for (client in clients) {
                    if (client.uid == myUid) continue
                    val pkg = resolveUid(client.uid) ?: continue
                    track(client.sessionId, pkg, client.uid, source, client.active != false, now)
                    found++
                }
            }
        } catch (e: Throwable) {
            DiagLog.e("Scan gagal", e)
            lastScanSummary.value = "Scan gagal: ${e.javaClass.simpleName}: ${e.message}"
            return
        }

        // Expire sessions we haven't seen for a while
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (now - s.lastSeen > SESSION_EXPIRE_MS) {
                DiagLog.i("Sesi ${s.sessionId} (${s.packageName}) hilang, efek dilepas")
                s.release()
                iterator.remove()
            } else if (s.lastSeen != now && s.source != "broadcast") {
                s.active = false
            }
        }

        lastActiveCount = sessions.values.count { it.active }
        lastScanSummary.value =
            "Sumber: $source, sesi ditemukan: $found, dipantau: ${sessions.size}, aktif: $lastActiveCount"
        publish()
    }

    private fun scanFlinger(now: Long): Int {
        val result = ShizukuShell.run("dumpsys media.audio_flinger")
        val tracks = AudioDumpParser.parseFlingerTracks(result.stdout)
        excerpt(result, "Session")
        if (tracks.isEmpty()) {
            val reason = "audio_flinger: ${result.stdout.length} karakter, exit=${result.exitCode}" +
                    (if (result.stderr.isNotBlank()) ", err=${result.stderr.take(200)}" else "")
            if (lastPolicyFailure?.startsWith("audio_flinger") != true) {
                DiagLog.w("Tidak ada track di $reason")
            }
            lastPolicyFailure = reason
            return 0
        }

        val myPid = Process.myPid()
        var found = 0
        for (t in tracks) {
            if (t.pid == myPid) continue
            val pkg = try {
                host.packageForPid(t.pid)
            } catch (e: Throwable) {
                DiagLog.w("Gagal memetakan pid ${t.pid}", e)
                null
            } ?: continue
            if (pkg == context.packageName) continue
            track(t.sessionId, pkg, -1, "audio_flinger", t.active, now)
            found++
        }
        return found
    }

    private fun excerpt(result: ShizukuShell.Result, keyword: String) {
        val lines = result.stdout.lines()
        val interesting = lines.filter { it.contains(keyword, ignoreCase = true) }.take(20)
        lastRawExcerpt.value = buildString {
            append("exit=").append(result.exitCode).append(", panjang=").append(result.stdout.length)
            if (result.timedOut) append(", TIMEOUT")
            if (result.stderr.isNotBlank()) append("\nstderr: ").append(result.stderr.take(300))
            append("\n")
            if (interesting.isEmpty()) {
                append(lines.take(15).joinToString("\n"))
            } else {
                append(interesting.joinToString("\n"))
            }
        }
    }

    private fun resolveUid(uid: Int): String? {
        if (uid < 0) return null
        val candidates = try {
            context.packageManager.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (candidates.isEmpty()) return null
        return host.packageForUid(uid, candidates)
    }

    private fun track(
        sessionId: Int,
        packageName: String,
        uid: Int,
        source: String,
        active: Boolean,
        now: Long
    ) {
        var s = sessions[sessionId]
        if (s != null && s.packageName != packageName) {
            // Session id reused by another app
            s.release()
            s = null
        }
        if (s == null) {
            s = SessionEffect(sessionId, packageName, uid, source)
            sessions[sessionId] = s
            DiagLog.i("Sesi baru $sessionId -> $packageName (uid $uid, via $source)")
        }
        s.lastSeen = now
        s.active = active
        if (s.effect == null && now - s.createFailedAt > EFFECT_RETRY_MS) {
            createEffect(s)
        }
        // Re-apply when the target volume changed or the effect was re-created
        if (s.effect != null && s.appliedVolume != host.volumeOf(packageName)) {
            applyTo(s)
        }
    }

    private fun createEffect(s: SessionEffect) {
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                false, 0, // pre EQ
                false, 0, // multi-band compressor
                false, 0, // post EQ
                true      // limiter (only enabled while boosting)
            ).setPreferredFrameDuration(10f).build()

            val dp = DynamicsProcessing(EFFECT_PRIORITY, s.sessionId, config)
            dp.setControlStatusListener { _, hasControl ->
                DiagLog.w("Sesi ${s.sessionId} (${s.packageName}): kontrol efek ${if (hasControl) "kembali" else "diambil aplikasi lain"}")
                if (hasControl) {
                    worker.execute {
                        s.appliedVolume = Float.NaN
                        applyTo(s)
                        publish()
                    }
                }
            }
            s.dynamics = dp
            s.createFailedAt = 0L
            s.error = null
            DiagLog.i("DynamicsProcessing terpasang di sesi ${s.sessionId} (${s.packageName}), kontrol=${dp.hasControl()}, channel=${dp.channelCount}")
            return
        } catch (e: Throwable) {
            DiagLog.w("DynamicsProcessing gagal di sesi ${s.sessionId} (${s.packageName}), coba Equalizer", e)
        }

        try {
            val eq = Equalizer(EFFECT_PRIORITY, s.sessionId)
            s.equalizer = eq
            s.eqRange = eq.bandLevelRange
            s.createFailedAt = 0L
            s.error = null
            DiagLog.i("Equalizer (cadangan) terpasang di sesi ${s.sessionId}, rentang ${s.eqRange?.joinToString()} mB")
        } catch (e: Throwable) {
            s.error = "Tidak bisa memasang efek: ${e.javaClass.simpleName}: ${e.message}"
            s.createFailedAt = System.currentTimeMillis()
            DiagLog.e("Efek gagal dipasang di sesi ${s.sessionId} (${s.packageName})", e)
        }
    }

    private fun applyTo(s: SessionEffect) {
        val volume = host.volumeOf(s.packageName).coerceIn(0f, MAX_VOLUME)
        val db = volumeToDb(volume)
        try {
            val dp = s.dynamics
            val eq = s.equalizer
            if (dp != null) {
                if (volume == 1f) {
                    dp.setEnabled(false)
                } else {
                    // Limiter prevents clipping/distortion when boosting
                    dp.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            true, volume > 1f, 0, 1f, 60f, 10f, -1f, 0f
                        )
                    )
                    dp.setInputGainAllChannelsTo(db)
                    dp.setEnabled(true)
                }
            } else if (eq != null) {
                val range = s.eqRange ?: shortArrayOf(-1500, 1500)
                val level = (db * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                for (band in 0 until eq.numberOfBands.toInt()) {
                    eq.setBandLevel(band.toShort(), level)
                }
                eq.setEnabled(volume != 1f)
            } else {
                return
            }
            if (s.appliedVolume != volume) {
                DiagLog.i("Volume ${s.packageName} sesi ${s.sessionId} = ${(volume * 100).roundToInt()}% (${"%.1f".format(db)} dB)")
            }
            s.appliedVolume = volume
            s.error = null
        } catch (e: Throwable) {
            s.error = "Gagal set volume: ${e.javaClass.simpleName}: ${e.message}"
            DiagLog.e("Gagal set volume sesi ${s.sessionId} (${s.packageName})", e)
            // The effect may be dead (session ended); recreate on next sighting
            s.release()
            s.appliedVolume = Float.NaN
        }
    }

    private fun publish() {
        val infos = sessions.values.sortedBy { it.packageName }.map { s ->
            SessionInfo(
                sessionId = s.sessionId,
                packageName = s.packageName,
                uid = s.uid,
                source = s.source,
                active = s.active,
                effect = when {
                    s.dynamics != null -> "DynamicsProcessing"
                    s.equalizer != null -> "Equalizer"
                    else -> "-"
                },
                hasControl = try {
                    s.effect?.hasControl() == true
                } catch (_: Throwable) {
                    false
                },
                appliedVolume = s.appliedVolume,
                error = s.error
            )
        }
        val byPackage = HashMap<String, Boolean>()
        for (s in sessions.values) {
            byPackage[s.packageName] = (byPackage[s.packageName] == true) || s.active
        }
        mainHandler.post {
            sessionInfos.value = infos
            host.onScanResult(byPackage)
        }
    }
}
