package dev.pipilot.app

import android.app.Application
import dev.pipilot.app.log.AppLog
import java.io.File

/**
 * Persist crash evidence: on any uncaught exception, write the stack plus recent
 * AppLog lines to files/crash-last.txt before the process dies. Next launch,
 * MainActivity reads it into AppLog (visible under Settings → View logs).
 * Physical devices without adb can still recover the crash stack.
 */
class PipilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Persist logs to filesDir: after the OS freezes/kills the process in the
        // background, the in-memory ring buffer is gone (that is why background
        // disconnects never captured the moment they happened).
        AppLog.init(filesDir)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(filesDir, "crash-last.txt").writeText(
                    buildString {
                        append("crash at ${System.currentTimeMillis()} thread=${thread.name}\n")
                        append(android.util.Log.getStackTraceString(e))
                        append("\n--- app log ---\n")
                        append(AppLog.dump())
                    },
                )
            }
            defaultHandler?.uncaughtException(thread, e)
        }
    }

    companion object {
        private const val BATTERY_PROMPT_MARK = "battery-opt-prompted"

        /** Read the last crash into AppLog on startup, then delete it. Returns true if a crash was found. */
        fun consumeLastCrash(filesDir: File): Boolean {
            val f = File(filesDir, "crash-last.txt")
            if (!f.exists()) return false
            val text = runCatching { f.readText() }.getOrNull()
            f.delete()
            if (text.isNullOrBlank()) return false
            AppLog.e("Crash", "last crash stack ↓")
            text.lineSequence().take(60).forEach { AppLog.e("Crash", it) }
            return true
        }

        /** Whether the battery-optimization prompt has already been shown (once only, so connect does not keep opening the system page). */
        fun batteryPromptShown(filesDir: File): Boolean = File(filesDir, BATTERY_PROMPT_MARK).exists()

        fun markBatteryPromptShown(filesDir: File) {
            runCatching { File(filesDir, BATTERY_PROMPT_MARK).writeText("1") }
        }
    }
}
