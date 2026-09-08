package dev.pipilot.app.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 内存环形日志:App 内操作与 RPC 收发的运行时证据。
 * - 用户从"设置 → 查看日志"复制发我,不再靠猜修 bug
 * - 容量 800 行,超了丢最旧的;只放内存,不写文件
 * - 敏感字段(密码/私钥/口令/token)在入口处打码,绝不原文记录
 */
object AppLog {

    enum class Level { D, I, W, E }

    data class Line(val time: String, val level: Level, val tag: String, val msg: String) {
        override fun toString(): String = "$time ${level.name}/$tag: $msg"
    }

    private const val CAP = 800
    private val queue = ConcurrentLinkedQueue<Line>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(level: Level, tag: String, msg: String) {
        queue.add(Line(timeFmt.format(Date()), level, tag, msg.take(1200)))
        while (queue.size > CAP) queue.poll()
    }

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String) = add(Level.E, tag, msg)

    /** 导出全文(分享用)。*/
    fun dump(): String = queue.joinToString("\n") { it.toString() }

    fun clear() = queue.clear()

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
