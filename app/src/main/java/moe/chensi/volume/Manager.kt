package moe.chensi.volume

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import moe.chensi.volume.data.App
import moe.chensi.volume.data.AppPreferencesStore
import moe.chensi.volume.engine.DiagLog
import moe.chensi.volume.engine.SessionVolumeEngine
import moe.chensi.volume.engine.ShizukuShell
import moe.chensi.volume.engine.VolumeLimits
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import moe.chensi.volume.system.NotificationManagerProxy
import moe.chensi.volume.system.PackageManagerProxy
import moe.chensi.volume.system.SystemVolume
import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@SuppressLint("PrivateApi")
class Manager(private val context: Context, dataStore: DataStore<Preferences>) {
    companion object {
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    }

    enum class ShizukuStatus {
        Uninstalled, Disconnected, PermissionDenied, Connected
    }

    enum class EngineMode {
        /** Not decided yet (Shizuku not connected) */
        Unknown,

        /** Original method: `IPlayer.setVolume`. Needs Android 13+ or Shizuku running as root. */
        PlayerVolume,

        /** Audio effect on each app's audio session. Works on Android 10+ without root. */
        SessionEffect
    }

    var engineMode by mutableStateOf(EngineMode.Unknown)
        private set

    /** Highest value of the per-app volume slider (1 = 100%). */
    val maxVolume: Float
        get() = if (engineMode == EngineMode.SessionEffect) SessionVolumeEngine.MAX_VOLUME else 1f

    private val started = AtomicBoolean(false)
    private var lastReloadApps = 0L

    val sessionEngine: SessionVolumeEngine by lazy {
        SessionVolumeEngine(context.applicationContext, object : SessionVolumeEngine.Host {
            override fun packageForPid(pid: Int): String? = this@Manager.packageForPid(pid)

            override fun packageForUid(uid: Int, candidates: Array<String>): String? {
                // Prefer a package with a launcher activity (the "real" app of a shared uid)
                for (name in candidates) {
                    if (getApp(name)?.hasAnyActivity == true) return name
                }
                return candidates.firstOrNull { getApp(it) != null } ?: candidates.first()
            }

            override fun volumeOf(packageName: String): Float = apps[packageName]?.volume ?: 1f

            override fun onScanResult(packages: Map<String, Boolean>) {
                for (app in apps.values) {
                    val playing = packages[app.packageName]
                    if (playing != null) {
                        app.updateSessionState(playing)
                    } else if (app.isPlaying) {
                        app.updateSessionState(false)
                    }
                }
            }
        })
    }

    private fun onAppVolumeChanged(app: App) {
        if (engineMode == EngineMode.SessionEffect) {
            sessionEngine.applyVolume(app.packageName)
        }
    }

    @EnableBinderProxy
    fun packageForPid(pid: Int): String? {
        val process = activityManager.runningAppProcesses?.find { it.pid == pid } ?: return null
        return process.pkgList?.firstOrNull { getApp(it) != null } ?: process.pkgList?.firstOrNull()
    }

    private var _shizukuStatus by mutableStateOf(ShizukuStatus.Disconnected)
    val shizukuStatus
        get() = _shizukuStatus

    val audioManager = context.getSystemService(AudioManager::class.java)!!.apply {
        Reflect.onClass(AudioManager::class.java).call("getService").get<Any>()
            .apply { ToggleableBinderProxy.wrap(this) }
    }

    val activityManager = context.getSystemService(ActivityManager::class.java)!!.apply {
        Reflect.onClass(ActivityManager::class.java).call("getService").get<Any>()
            .apply { ToggleableBinderProxy.wrap(this) }
    }
    private val packageManager by lazy { PackageManagerProxy.get(context) }
    val notificationManagerProxy = NotificationManagerProxy(context)

    private val appPreferencesStore = AppPreferencesStore(dataStore)
    private val _systemSliderVisibility = mutableStateMapOf<String, Boolean>()
    val systemSliderVisibility: Map<String, Boolean>
        get() = _systemSliderVisibility

    fun isSystemSliderVisible(id: String): Boolean {
        return _systemSliderVisibility[id] ?: true
    }

    fun setSystemSliderVisible(id: String, visible: Boolean) {
        if ((_systemSliderVisibility[id] ?: true) == visible) {
            return
        }

        _systemSliderVisibility[id] = visible
        appPreferencesStore.setSystemSliderVisible(id, visible)
    }

    val apps = mutableStateMapOf<String, App>()

    private fun reloadApps() {
        for (packageInfo in packageManager.getInstalledPackagesForAllUsers()) {
            val appInfo = packageInfo.applicationInfo ?: continue
            if (!apps.containsKey(packageInfo.packageName)) {
                apps[packageInfo.packageName] = App(
                    packageManager,
                    packageInfo,
                    packageManager.loadLabel(appInfo),
                    appPreferencesStore.getOrCreate(packageInfo.packageName),
                    appPreferencesStore::save,
                    ::onAppVolumeChanged
                )
            }
        }
    }

    private fun getApp(packageName: String): App? {
        val app = apps[packageName]
        if (app != null) {
            return app
        }

        // Maybe just installed? Don't rescan all packages more than once every 5 seconds.
        val now = System.currentTimeMillis()
        if (now - lastReloadApps < 5000) {
            return null
        }
        lastReloadApps = now
        reloadApps()
        return apps[packageName]
    }

    @EnableBinderProxy
    private fun initialize() {
        reloadApps()

        val shizukuUid = ShizukuShell.uid()
        engineMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || shizukuUid == 0) {
            EngineMode.PlayerVolume
        } else {
            EngineMode.SessionEffect
        }
        DiagLog.i(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                    "${Build.MANUFACTURER} ${Build.MODEL}, Shizuku uid=$shizukuUid -> mesin $engineMode"
        )

        VolumeLimits.max = maxVolume

        // Avoid "Not allowed to change Do Not Disturb state" when ring volume hits 0
        // (separate thread: this function routes binder calls through Shizuku, which would make
        // the permission check run as the shell user instead of this app)
        thread(name = "dnd-access") { SystemVolume.ensureDndAccess(context) }

        if (engineMode == EngineMode.SessionEffect) {
            sessionEngine.start()
            return
        }

        val playbackConfigurations = audioManager.activePlaybackConfigurations
        processAudioPlaybackConfigurations(playbackConfigurations)

        audioManager.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    for (app in apps.values) {
                        app.clearPlayers()
                    }
                    processAudioPlaybackConfigurations(configs)
                }
            }, null
        )
    }

    @SuppressLint("DiscouragedPrivateApi")
    @EnableBinderProxy
    fun processAudioPlaybackConfigurations(configs: List<AudioPlaybackConfiguration>) {
        val runningProcesses = activityManager.runningAppProcesses

        for (config in configs) {
            val proxy = AudioPlaybackConfigurationProxy(config)

            val pid = proxy.clientPid
            val process = runningProcesses.find { process -> process.pid == pid } ?: continue

            // A process can host several packages (shared uid); prefer one we know
            val packageName = process.pkgList?.firstOrNull { apps.containsKey(it) }
                ?: process.pkgList?.firstOrNull() ?: continue
            val app = getApp(packageName) ?: continue

            app.addPlayer(proxy)
        }
    }

    init {
        val isShizukuInstalled = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isShizukuInstalled) {
            _shizukuStatus = ShizukuStatus.Uninstalled
        } else if (!Shizuku.pingBinder()) {
            _shizukuStatus = ShizukuStatus.Disconnected
        }

        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.isPreV11()) {
                return@addBinderReceivedListenerSticky
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            } else {
                _shizukuStatus = ShizukuStatus.PermissionDenied
            }
        }

        Shizuku.addBinderDeadListener {
            _shizukuStatus = ShizukuStatus.Disconnected
            DiagLog.w("Koneksi Shizuku terputus")
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus = ShizukuStatus.Connected
                start()
            }
        }

        ShizukuProvider.requestBinderForNonProviderProcess(context)
    }

    private fun start() {
        // Both the binder listener and the permission listener can call this
        if (!started.compareAndSet(false, true)) {
            if (engineMode == EngineMode.SessionEffect) {
                // Shizuku reconnected: rescan right away
                sessionEngine.scheduleScan(0)
            }
            return
        }

        appPreferencesStore.track { first ->
            for ((packageName, index) in appPreferencesStore.indices) {
                if (!first) {
                    // Replace with new reference
                    apps[packageName]?.setPreferences(appPreferencesStore.values[index])
                }
            }

            _systemSliderVisibility.clear()
            _systemSliderVisibility.putAll(appPreferencesStore.systemSliderVisibility)

            if (first) {
                initialize()
            }
        }
    }
}
