package dev.pipilot.app.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runtime evidence: in-memory ring (in-app viewer) + on-disk file (for sharing diagnostics).
 *
 * Why disk is required: when a background disconnect happens the process may be frozen
 * or killed, and memory logs die with it — that is why "background disconnect" never
 * captured the moment it occurred. Disk only records I/W/E (lifecycle-level);
 * D (per-RPC send/recv) stays in memory so the file is not flooded and key lines are
 * not pushed out.
 *
 * - Memory capacity 800 lines; file rotates at 512KB (keeps previous pipilot.log.1)
 * - Flush every line so a kill never drops the last line
 * - Sensitive fields (password / private key / passphrase / token) are redacted at the entry point
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
     * Called from Application.onCreate to enable disk logging. If the directory is
     * not writable, silently degrade to in-memory logs only.
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

    /** Log file path (for sharing); null if init was never called. */
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
        // D is per-RPC noise: keep it in memory for in-app debugging, do not write to disk
        if (level != Level.D) {
            val ts = fsyncFmt?.format(Date()) ?: return
            appendToFile("$ts ${level.name}/$tag: $clipped")
        }
    }

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun i(tag: String, msg: String) = add(Level.I, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)
    fun e(tag: String, msg: String) = add(Level.E, tag, msg)

    /** Full dump (for sharing). */
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
     * Redact an outbound RPC command: keep type/id, other fields as length only,
     * so prompt bodies and workDir paths never enter the log. Input is already
     * a single-line serialized JSON.
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
