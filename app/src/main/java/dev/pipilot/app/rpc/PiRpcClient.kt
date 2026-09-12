package dev.pipilot.app.rpc

import android.util.Log
import dev.pipilot.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * pi RPC 客户端:负责严格 JSONL 读写、请求-响应关联、事件分发。
 *
 * 协议要点(docs/rpc.md#framing):
 * - 只按 \n 分帧,容忍行尾 \r;绝不能用按 Unicode 分隔符断行的通用行读取器
 * - 每行一个 JSON 对象;stdin 每条命令一行
 */
class PiRpcClient(
    private val stdin: java.io.OutputStream,
    private val stdout: java.io.InputStream,
    private val stderr: java.io.InputStream,
    /** 阻塞等待远端命令退出并返回退出码;null 表示拿不到(老通道)。用于诊断"命令启动即退"。 */
    private val remoteExit: (suspend () -> Int?)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Ready : ConnectionState
        data class Closed(val reason: String?) : ConnectionState
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _events = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PiEvent> = _events.asSharedFlow()

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 原始行流,调试用。*/
    val rawLines: SharedFlow<String> = _lines.asSharedFlow()

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<PiResponse>>()

    private val writerMutex = Mutex()
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var exitJob: Job? = null

    // stderr 尾巴:远端命令退出时的真实原因(shell 报错、pi 崩溃栈)在这里,断连时展示给用户
    private val stderrTail = StringBuilder()

    private fun noteStderr(text: String) {
        synchronized(stderrTail) {
            stderrTail.append(text)
            if (stderrTail.length > 1500) stderrTail.delete(0, stderrTail.length - 1500)
        }
    }

    private fun stderrTailText(): String =
        synchronized(stderrTail) { stderrTail.toString().trim() }

    /** 标记连接关闭并快速失败所有未决请求;重复调用无害。 */
    private fun markClosed(reason: String?) {
        val cur = _connection.value
        if (cur is ConnectionState.Closed) {
            // 已关闭:允许用更具体的原因覆盖通用原因,让用户看到 exit 码和 stderr
            if (reason != null && (cur.reason == null || cur.reason == "stdout closed")) {
                _connection.value = ConnectionState.Closed(reason)
            }
            return
        }
        _connection.value = ConnectionState.Closed(reason)
        pending.values.forEach { it.complete(PiResponse(id = null, command = "", success = false, error = reason ?: "connection closed", data = null)) }
        pending.clear()
    }

    fun start() {
        writer = BufferedWriter(OutputStreamWriter(stdin, Charsets.UTF_8))
        readerJob = scope.launch { readLoop() }
        stderrJob = scope.launch { drainStderr() }
        exitJob = scope.launch {
            // 等远端命令退出:pi 正常常驻时永远不返回;启动即退(未安装/崩溃)时在这里拿到真相
            val code = runCatching { remoteExit?.invoke() }.getOrNull() ?: return@launch
            kotlinx.coroutines.delay(200) // 等 stderr 尾巴沉淀
            val tail = stderrTailText()
            markClosed(
                if (tail.isNotEmpty()) "远程命令退出(exit=$code): ${tail.take(300)}"
                else "远程命令退出(exit=$code)",
            )
        }
    }

    suspend fun close() {
        markClosed(_connection.value.let { if (it is ConnectionState.Closed) it.reason else null })
        runCatching { stdin.close() }
        readerJob?.cancel()
        stderrJob?.cancel()
        exitJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * 发送一条命令(已序列化的 JSON 单行)。
     * 写失败(远端命令退出/通道被关)绝不抛给调用方:调用方多在 viewModelScope 主线程
     * 协程里,异常会直接杀死进程(闪退)。这里降级为标记 Closed + 快失败未决请求,
     * 由 VM 的连接状态监听走重连流程,并把 stderr 里的真实原因带给用户。
     */
    suspend fun sendLine(line: String) = withContext(Dispatchers.IO) {
        writerMutex.withLock {
            val w = writer ?: return@withLock
            try {
                // 协议要求 LF 结尾;禁用平台换行符(Windows 上 \r\n 会破坏 JSONL)
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                AppLog.e(TAG, "write failed: ${e.javaClass.simpleName}: ${e.message}; marking closed")
                val tail = stderrTailText()
                markClosed(
                    if (tail.isNotEmpty()) "远程命令退出: ${tail.take(300)}"
                    else "连接写入失败: ${e.message ?: e.javaClass.simpleName}",
                )
                return@withLock
            }
        }
        Log.d(TAG, ">> ${line.take(200)}")
    }

    /** 发送命令并等待对应 response(按 id 关联)。超时返回 null。*/
    suspend fun request(line: String, id: String, timeoutMs: Long = 60_000): PiResponse? {
        val deferred = kotlinx.coroutines.CompletableDeferred<PiResponse>()
        pending[id] = deferred
        val t0 = System.currentTimeMillis()
        AppLog.i(TAG, ">> ${AppLog.redactCommand(line)}")
        try {
            sendLine(line)
            val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
            val cost = System.currentTimeMillis() - t0
            if (resp == null) {
                AppLog.w(TAG, "<< TIMEOUT id=$id after ${cost}ms")
            } else {
                val dataKeys = (resp.data as? kotlinx.serialization.json.JsonObject)?.keys?.joinToString(",")
                AppLog.i(TAG, "<< response id=$id cmd=${resp.command} success=${resp.success} error=${resp.error} dataKeys=[$dataKeys] (${cost}ms)")
            }
            return resp
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun readLoop() {
        // 手工按 \n 分帧:不能用 BufferedReader.readLine()(它按行终止符集合切分,
        // 在某些实现下会把 U+2028/U+2029 当行尾,违反 pi 的 strict JSONL 语义)。
        // 这里用 InputStreamReader 单字符读取 + 缓冲;JSONL 行通常较长,性能足够(事件频率 <100/s)。
        val reader = BufferedReader(InputStreamReader(stdout, Charsets.UTF_8), 64 * 1024)
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = reader.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buf[i]
                    if (c == '\n') {
                        handleLine(sb.toString())
                        sb.setLength(0)
                    } else {
                        sb.append(c)
                    }
                }
            }
            if (sb.isNotEmpty()) handleLine(sb.toString())
            // stdout 关闭 = 远端命令退出。稍等一下让 stderr 尾巴和 exit 监听先到,
            // 这样 Closed 原因能带上真实错误(如 "command not found: pi")而非泛泛的 stdout closed
            kotlinx.coroutines.delay(400)
            val tail = stderrTailText()
            markClosed(if (tail.isNotEmpty()) "远程命令退出: ${tail.take(300)}" else "stdout closed")
        } catch (e: Exception) {
            markClosed(e.message)
        }
    }

    private suspend fun handleLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isBlank()) return
        Log.d(TAG, "<< ${line.take(200)}")
        _lines.emit(line)
        val parsed = try {
            parseLine(line)
        } catch (e: Exception) {
            AppLog.w(TAG, "<< BAD JSON line (${line.length} chars): ${e.message} head=${line.take(120)}")
            Log.w(TAG, "bad json line: ${e.message}")
            return
        }
        when (parsed) {
            is PiLine.Response -> {
                val resp = PiResponse.from(parsed.value)
                val id = resp.id
                if (id != null) {
                    if (pending.remove(id)?.complete(resp) == null) {
                        AppLog.w(TAG, "<< UNMATCHED response id=$id cmd=${resp.command} success=${resp.success} error=${resp.error}")
                    }
                } else {
                    AppLog.w(TAG, "<< response WITHOUT id cmd=${resp.command} success=${resp.success}")
                }
            }
            is PiLine.Event -> _events.emit(PiEvent(type = parsed.value.str("type") ?: "unknown", raw = parsed.value))
            is PiLine.ExtensionUiRequest -> _events.emit(PiEvent(type = "extension_ui_request", raw = parsed.value))
            is PiLine.Unknown -> Unit
        }
    }

    private suspend fun drainStderr() {
        val reader = BufferedReader(InputStreamReader(stderr, Charsets.UTF_8))
        try {
            val buf = CharArray(4096)
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = reader.read(buf)
                if (n < 0) break
                val text = String(buf, 0, n)
                noteStderr(text)
                Log.w(TAG, "stderr: ${text.take(500)}")
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PiRpcClient"
    }
}
