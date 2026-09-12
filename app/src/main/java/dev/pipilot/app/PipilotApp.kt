package dev.pipilot.app

import android.app.Application
import dev.pipilot.app.log.AppLog
import java.io.File

/**
 * 崩溃证据落盘:任何未捕获异常在进程退出前,把堆栈+近期 AppLog 写到
 * files/crash-last.txt;下次启动 MainActivity 读出并入 AppLog(设置→查看日志可见)。
 * 没有连接 adb 的真机也能拿到闪退堆栈,不再靠猜修 bug。
 */
class PipilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
        /** 启动时读取上次崩溃并入 AppLog,读完即删。返回 true 表示有崩溃记录。 */
        fun consumeLastCrash(filesDir: File): Boolean {
            val f = File(filesDir, "crash-last.txt")
            if (!f.exists()) return false
            val text = runCatching { f.readText() }.getOrNull()
            f.delete()
            if (text.isNullOrBlank()) return false
            AppLog.e("Crash", "上次闪退堆栈 ↓")
            text.lineSequence().take(60).forEach { AppLog.e("Crash", it) }
            return true
        }
    }
}
