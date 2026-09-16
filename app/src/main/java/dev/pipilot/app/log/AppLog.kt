package dev.pipilot.app.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 运行时证据:内存环形(给 App 内查看)+ 落盘(给回传诊断)。
 *
 * 为什么必须落盘:后台断链时进程可能被系统冻结/杀掉,内存日志随进程一起消失 ——
 * 之前每次"切后台断线"都抓不到断链瞬间就是因为这个。落盘只记 I/W/E(生命周期级),
 * D 级(每条 RPC 命令的收发)只进内存,避免把文件刷爆、也避免关键行被挤掉。
 *
 * - 内存容量 800 行,文件 512KB 轮转(保留上一份 pipilot.log.1)
 * - 每行 flush:进程随时被杀也不丢最后一行
 * - 敏感字段(密码/私钥/口令/token)在入口处打码,绝不原文记录
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Line(val time: String, val level: Level, val tag: String, val msg: String) {
        override fun toString(): String = "$time ${level.name}/$tag: $msg"
    }

    private const val CAP = 800
    private const val FILE_NAME = "pipilot.log"
    private const val FILE_MAX_BYTES = 512L * 1024

    private val queue = ConcurrentLinkedQueue<Line>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var fsyncFmt: SimpleDateFormat? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var fileBytes = 0L

    /**
     * 由 Application.onCreate 调用,开启落盘。目录不可写时静默降级为纯内存日志。
     */
    @Synchronized
    fun init(dir: File) {
        runCatching {
            val logDir = File(dir, "logs").apply { mkdirs() }
            val f = File(logDir, FILE_NAME)
            val legacy = File(dir, FILE_NAME)
            if (!f.exists() && legacy.exists()) legacy.renameTo(f)
            file = f
            fileBytes = f.length()
            fsyncFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            writer = BufferedWriter(FileWriter(f, true))
            if (fileBytes > FILE_MAX_BYTES) rotateLocked()
        }.onFailure { writer = null }
    }

    /** 日志文件路径(分享用);未 init 时为 null。*/
    fun logFile(): File? = file?.takeIf { it.exists() }

    @Synchronized
    private fun rotateLocked() {
        runCatching {
            writer?.close()
            val f = file ?: return
            File(f.parentFile, "$FILE_NAME.1").also { old ->
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            writer = BufferedWriter(FileWriter(f, false))
            fileBytes = 0L
        }.onFailure { writer = null }
    }

    @Synchronized
    private fun appendToFile(line: String) {
        val w = writer ?: return
        runCatching {
            w.write(line)
            w.newLine()
            w.flush()
            fileBytes += line.length + 1
            if (fileBytes > FILE_MAX_BYTES) rotateLocked()
        }.onFailure { writer = null }
    }

    @Synchronized
    fun add(level: Level, tag: String, msg: String) {
        val clipped = msg.take(1200)
        queue.add(Line(timeFmt.format(Date()), level, tag, clipped))
        while (queue.size > CAP) queue.poll()
        // D 级是每条 RPC 命令级别的噪音:留在内存给 App 内排查,不落盘
        if (level != Level.D) {
            val ts = fsyncFmt?.format(Date()) ?: return
            appendToFile("$ts ${level.name}/$tag: $clipped")
        }
    }

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String) = add(Level.E, tag, msg)

    /** 导出全文(分享用)。*/
    fun dump(): String = queue.joinToString("\n") { it.toString() }

    @Synchronized
    fun clear() {
        queue.clear()
        runCatching {
            writer?.close()
            val f = file
            if (f != null && f.exists()) f.writeText("")
            fileBytes = 0L
            writer = f?.let { BufferedWriter(FileWriter(it, true)) }
        }.onFailure { writer = null }
    }

    /**
     * 发出的 RPC 命令脱敏:保留 type/id,其余字段只留长度,防止 prompt 内容
     * 和 workDir 等路径进日志。已是单行的序列化 JSON。
     */
    fun redactCommand(line: String): String {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(line)
                as? kotlinx.serialization.json.JsonObject ?: return line.take(200)
            val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
            val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "-"
            val extras = obj.keys.filter { it != "type" && it != "id" }
            "type=$type id=$id fields=[${extras.joinToString(",")}] len=${line.length}"
        } catch (_: Exception) {
            line.take(200)
        }
    }
}
