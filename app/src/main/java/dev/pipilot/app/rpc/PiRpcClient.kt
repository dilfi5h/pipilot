package dev.pipilot.app.rpc

import dev.pipilot.app.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * pi RPC client: strict JSONL I/O, request/response correlation, event fan-out.
 *
 * Protocol notes (docs/rpc.md#framing):
 * - Frame on \n only; tolerate a trailing \r; never use a generic line reader that splits on Unicode separators
 * - One JSON object per line; one command per stdin line
 */
class PiRpcClient(
    private val stdin: java.io.OutputStream,
    private val stdout: java.io.InputStream,
    private val stderr: java.io.InputStream,
    /** Block until the remote command exits and return its code; null if unavailable (legacy channel). Used to diagnose "exited on start". */
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
    /** Raw line stream for debugging. */
    val rawLines: SharedFlow<String> = _lines.asSharedFlow()

    private val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<PiResponse>>()

    /**
     * Timestamp of the latest inbound data (response / event / stderr).
     * Do not judge link health by whether one request answered: during a long tool call the
     * command response may stall while events still flow → inbound traffic means the link is
     * alive, so we avoid a false reconnect that kills a running agent.
     */
    @Volatile
    private var lastInboundAt = System.currentTimeMillis()
    val lastInboundAtMs: Long get() = lastInboundAt
    /** Set during local teardown so close() does not emit Closed and push the VM into a second reconnect loop. */
    @Volatile
    private var suppressClosedNotify = false

    private val writerMutex = Mutex()
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var exitJob: Job? = null

    // stderr tail: the real reason the remote command exited (shell error, pi crash stack); shown on disconnect
    private val stderrTail = StringBuilder()

    private fun noteStderr(text: String) {
        synchronized(stderrTail) {
            stderrTail.append(text)
            if (stderrTail.length > 1500) stderrTail.delete(0, stderrTail.length - 1500)
        }
    }

    private fun stderrTailText(): String =
        synchronized(stderrTail) { stderrTail.toString().trim() }

    /** Mark the connection closed and fail all pending requests quickly; safe to call repeatedly. */
    private fun markClosed(reason: String?) {
        val cur = _connection.value
        if (cur is ConnectionState.Closed) {
            // Already closed: allow a more specific reason to replace a generic one so the user sees exit code / stderr
            if (reason != null && (cur.reason == null || cur.reason == "stdout closed") && !suppressClosedNotify) {
                AppLog.i(TAG, "Closed reason upgraded: ${cur.reason} -> $reason")
                _connection.value = ConnectionState.Closed(reason)
            }
            return
        }
        pending.values.forEach { it.complete(PiResponse(id = null, command = "", success = false, error = reason ?: "connection closed", data = null)) }
        pending.clear()
        if (suppressClosedNotify) {
            AppLog.i(TAG, "markClosed suppressed (local teardown): $reason")
            return
        }
        AppLog.i(TAG, "markClosed: $reason")
        _connection.value = ConnectionState.Closed(reason)
    }

    fun start() {
        writer = BufferedWriter(OutputStreamWriter(stdin, Charsets.UTF_8))
        readerJob = scope.launch { readLoop() }
        stderrJob = scope.launch { drainStderr() }
        exitJob = scope.launch {
            // Wait for remote exit: a healthy pi never returns; an immediate exit (not installed / crash) surfaces here
            val code = runCatching { remoteExit?.invoke() }.getOrNull() ?: return@launch
            kotlinx.coroutines.delay(200) // let the stderr tail settle
            val tail = stderrTailText()
            markClosed(
                if (tail.isNotEmpty()) "Remote command exited (exit=$code): ${tail.take(300)}"
                else "Remote command exited (exit=$code)",
            )
        }
    }

    /**
     * @param notify when false, tear down the channel without emitting Closed. Local VM teardown
     *   (forceReconnect / closeResources) already enters reconnect; another Closed would stack a second loop.
     */
    suspend fun close(notify: Boolean = true) {
        if (!notify) suppressClosedNotify = true
        markClosed(
            _connection.value.let { if (it is ConnectionState.Closed) it.reason else "closed locally" },
        )
        runCatching { stdin.close() }
        readerJob?.cancel()
        stderrJob?.cancel()
        exitJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    /**
     * Send one command (already a single-line JSON).
     * Write failures (remote exited / channel closed) must not throw to the caller: callers often
     * run on the viewModelScope main-thread coroutine, and an exception would kill the process.
     * Degrade to markClosed + fail pending requests; the VM connection observer reconnects and
     * surfaces the real stderr reason.
     */
    suspend fun sendLine(line: String) = withContext(Dispatchers.IO) {
        writerMutex.withLock {
            val w = writer ?: return@withLock
            try {
                // Protocol requires an LF terminator; never use the platform newline (Windows \r\n breaks JSONL)
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                AppLog.e(TAG, "write failed: ${e.javaClass.simpleName}: ${e.message}; marking closed")
                val tail = stderrTailText()
                markClosed(
                    if (tail.isNotEmpty()) "Remote command exited: ${tail.take(300)}"
                    else "Connection write failed: ${e.message ?: e.javaClass.simpleName}",
                )
                return@withLock
            }
        }
        AppLog.d(TAG, ">> ${AppLog.redactCommand(line)}")
    }

    /** Send a command and wait for the matching response (correlated by id). Returns null on timeout. */
    suspend fun request(line: String, id: String, timeoutMs: Long = 60_000): PiResponse? {
        val deferred = kotlinx.coroutines.CompletableDeferred<PiResponse>()
        pending[id] = deferred
        val t0 = System.currentTimeMillis()
        // One D-level line per command (memory only): RPC command noise would push the disconnect moment out of the 800-line ring
        AppLog.d(TAG, ">> ${AppLog.redactCommand(line)}")
        try {
            sendLine(line)
            val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
            val cost = System.currentTimeMillis() - t0
            if (resp == null) {
                AppLog.w(TAG, "<< TIMEOUT id=$id after ${cost}ms")
            } else {
                val dataKeys = (resp.data as? kotlinx.serialization.json.JsonObject)?.keys?.joinToString(",")
                val detail = "<< response id=$id cmd=${resp.command} success=${resp.success} error=${resp.error} dataKeys=[$dataKeys] (${cost}ms)"
                // Successful responses are noise; failures are evidence
                if (resp.success) AppLog.d(TAG, detail) else AppLog.w(TAG, detail)
            }
            return resp
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun readLoop() {
        // Frame manually on \n: do not use BufferedReader.readLine() (it splits on a set of
        // terminators and some implementations treat U+2028/U+2029 as EOL, violating pi's strict JSONL).
        // Use InputStreamReader with a buffer; JSONL lines are long enough that this is fine (<100 events/s).
        val reader = BufferedReader(InputStreamReader(stdout, Charsets.UTF_8), 64 * 1024)
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = reader.read(buf)
                if (n < 0) break
                lastInboundAt = System.currentTimeMillis()
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
            // stdout closed = remote command exited. Wait briefly so the stderr tail and exit
            // watcher arrive first, so Closed carries a real error (e.g. "command not found: pi")
            // instead of a generic "stdout closed".
            kotlinx.coroutines.delay(400)
            val tail = stderrTailText()
            markClosed(if (tail.isNotEmpty()) "Remote command exited: ${tail.take(300)}" else "stdout closed")
        } catch (e: Exception) {
            AppLog.e(TAG, "readLoop failed: ${e.javaClass.simpleName}: ${e.message}")
            markClosed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun handleLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isBlank()) return
        AppLog.d(TAG, "<< ${AppLog.redactCommand(line)}")
        _lines.emit(line)
        val parsed = try {
            parseLine(line)
        } catch (e: Exception) {
            AppLog.w(TAG, "<< BAD JSON line (${line.length} chars): ${e.message} head=${line.take(120)}")
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
                lastInboundAt = System.currentTimeMillis()
                val text = String(buf, 0, n)
                noteStderr(text)
                AppLog.w(TAG, "stderr: ${text.take(500)}")
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PiRpcClient"
    }
}
