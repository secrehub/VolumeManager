package moe.chensi.volume.engine

import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import kotlin.concurrent.thread

/**
 * Runs shell commands with Shizuku's privileges (`shell` user in ADB mode, `root` in root mode).
 */
object ShizukuShell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
        val ok: Boolean
            get() = exitCode == 0 && !timedOut
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** uid Shizuku runs as: 0 = root, 2000 = shell (ADB), -1 = unknown. */
    fun uid(): Int {
        return try {
            Shizuku.getUid()
        } catch (_: Throwable) {
            -1
        }
    }

    fun version(): Int {
        return try {
            Shizuku.getVersion()
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Run [command] through `sh -c`. Output is read on separate threads so a large
     * output can't dead-lock the process.
     */
    fun run(command: String, timeoutMs: Long = 8000): Result {
        // `Shizuku.newProcess` is private in Shizuku API 13, but still works.
        val process = Reflect.onClass(Shizuku::class.java).call(
            "newProcess", arrayOf("sh", "-c", command), null, null
        ).get<ShizukuRemoteProcess>()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = thread(name = "shizuku-out") {
            try {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            } catch (_: Throwable) {
            }
        }
        val errThread = thread(name = "shizuku-err") {
            try {
                process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
            } catch (_: Throwable) {
            }
        }

        // Note: `ShizukuRemoteProcess.waitFor(timeout, unit)` returns false immediately on
        // some Shizuku versions, so wait with the plain `waitFor()` on a helper thread.
        var exitCode = -1
        val waiter = thread(name = "shizuku-wait") {
            exitCode = try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
            }
        }
        waiter.join(timeoutMs)
        val finished = !waiter.isAlive
        if (!finished) {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }
        // If waiting failed early, the output (until EOF) is still the best signal of completion
        outThread.join(if (finished && exitCode == -1) timeoutMs else 2000)
        errThread.join(1000)

        return Result(
            exitCode,
            synchronized(stdout) { stdout.toString() },
            synchronized(stderr) { stderr.toString() },
            !finished
        )
    }
}
