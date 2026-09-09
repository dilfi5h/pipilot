package dev.pipilot.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pipilot.app.chat.ChatItem
import dev.pipilot.app.chat.StreamReducer
import dev.pipilot.app.log.AppLog
import dev.pipilot.app.rpc.PiCommands
import dev.pipilot.app.rpc.PiEvent
import dev.pipilot.app.rpc.PiModel
import dev.pipilot.app.rpc.PiResponse
import dev.pipilot.app.rpc.PiRpcClient
import dev.pipilot.app.rpc.PiState
import dev.pipilot.app.rpc.parseLine
import dev.pipilot.app.rpc.PiLine
import dev.pipilot.app.rpc.piJson
import dev.pipilot.app.settings.ConnectionSettings
import dev.pipilot.app.settings.SettingsStore
import dev.pipilot.app.ssh.SshConfig
import dev.pipilot.app.ssh.SshConnector
import dev.pipilot.app.ssh.SshExecSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import dev.pipilot.app.rpc.str

/** 扩展弹出的 UI 请求(select/confirm/input),转成 Android 对话框。*/
data class UiDialog(
    val id: String,
    val method: String,
    val title: String,
    val message: String?,
    val options: List<String>,
    val placeholder: String?,
    val prefill: String?,
)

data class SessionEntryInfo(
    val path: String,
    val name: String,
    val mtime: String,
)

data class UiState(
    val connectionLabel: String = "未连接",
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectCountdown: Int = 0,
    val logSeq: Long = 0,
    val error: String? = null,
    val items: List<ChatItem> = emptyList(),
    val state: PiState? = null,
    val models: List<PiModel> = emptyList(),
    val thinkingLevels: List<String> = emptyList(),
    val sessions: List<SessionEntryInfo> = emptyList(),
    val queueSteering: List<String> = emptyList(),
    val queueFollowUp: List<String> = emptyList(),
    val dialog: UiDialog? = null,
    val statsText: String? = null,
)

class PiViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<ConnectionSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var ssh: SshExecSession? = null
    private var client: PiRpcClient? = null
    private val reducer = StreamReducer()

    // 最近一次已连接会话的 session 文件;重连成功后用 `pi --mode rpc --session <path>` 恢复,
    // 避免"断了丢现场"(新起的 pi 进程默认是空会话)
    private var lastSessionFile: String? = null

    private val finalizedTools = HashMap<String, Boolean>()

    // 重连控制:connectEpoch 隔离过期循环,userDisconnect 标记手动断开
    private var connectEpoch = 0
    @Volatile private var userDisconnect = false
    private var reconnectJob: Job? = null

    fun saveSettings(s: ConnectionSettings) {
        viewModelScope.launch { settingsStore.save(s) }
    }

    fun connect() {
        userDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        doConnect(resetItems = true, epoch = ++connectEpoch)
    }

    /**
     * 一次连接尝试。成功返回 true。
     * @param epoch 所属连接代次;重连与手动 connect 各占一代,过期尝试的结果会被丢弃。
     * @param resetItems 成功后是否清空聊天列表(手动连接清空,重连保留后由 loadHistory 刷新)。
     */
    private suspend fun attemptConnect(epoch: Int, resetItems: Boolean = true): Boolean {
        val s = settings.value
        if (!s.isValid) return false
        val auth = if (s.authType == "key") {
            SshConfig.Auth.PrivateKey(s.privateKey, s.keyPassphrase.ifBlank { null })
        } else {
            SshConfig.Auth.Password(s.password)
        }
        return try {
            val cd = if (s.workDir.isNotBlank()) "cd ${shellQuote(s.workDir)} && " else ""
            // 重连(resetItems=false)时带上次会话路径启动,恢复原会话;手动连接总是全新会话
            val resumeArg = if (!resetItems) lastSessionFile?.let { " --session ${shellQuote(it)}" } ?: "" else ""
            val exec = SshConnector.connectAndExec(
                SshConfig(s.host, s.port.toIntOrNull() ?: 22, s.user, auth),
                "${cd}${s.piCommand}$resumeArg",
            )
            if (epoch != connectEpoch) {
                // 已过期:关掉刚建好的连接,结果作废
                runCatching { exec.close() }
                return false
            }
            ssh = exec
            val rpc = PiRpcClient(exec.stdin, exec.stdout, exec.stderr)
            client = rpc
            rpc.start()
            observeClient(rpc, epoch)
            _ui.value = _ui.value.copy(
                connected = true, connecting = false,
                reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                connectionLabel = "已连接",
                items = if (resetItems) emptyList() else _ui.value.items,
            )
            refreshAll()
            AppLog.i(TAG, "connect ok${if (!resetItems) " (resumed $lastSessionFile)" else ""}")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "connect attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "connect failed", e)
            if (epoch == connectEpoch && !userDisconnect && _ui.value.reconnecting) {
                // 重连中的失败不弹 error:倒计时横幅已说明情况,避免每次重试都弹 Snackbar
            } else if (epoch == connectEpoch) {
                _ui.value = _ui.value.copy(error = e.message)
            }
            false
        }
    }

    private fun doConnect(resetItems: Boolean, epoch: Int) {
        if (_ui.value.connecting) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                connecting = true, error = null,
                reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                items = if (resetItems) emptyList() else _ui.value.items,
                connectionLabel = "连接中…",
            )
            val ok = attemptConnect(epoch)
            if (!ok && epoch == connectEpoch) {
                _ui.value = _ui.value.copy(connected = false, connecting = false, connectionLabel = "未连接")
            }
        }
    }

    fun disconnect() {
        // 手动断开:新开一代,让正在进行的连接/重连全部过期作废
        connectEpoch++
        userDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        viewModelScope.launch {
            closeResources()
            _ui.value = _ui.value.copy(
                connected = false, connecting = false,
                reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                connectionLabel = "未连接", dialog = null,
            )
        }
    }

    /** 用户在重连横幅上点"取消":等同于手动断开,不再重试。*/
    fun cancelReconnect() = disconnect()

    /**
     * 意外断开后的自动重连循环:指数退避 2s → 4s → 8s … 30s 封顶,无限重试直到
     * 成功、用户手动断开/取消,或 ViewModel 销毁。等待期间每秒刷新倒计时 UI。
     */
    private fun scheduleReconnect(reason: String?) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val epoch = ++connectEpoch
            var delaySecs = RECONNECT_BASE_SECS
            var attempt = 0
            while (isActive && epoch == connectEpoch && !userDisconnect) {
                attempt++
                // 倒计时(可被取消打断)
                for (left in delaySecs downTo 1) {
                    if (!isActive || epoch != connectEpoch || userDisconnect) return@launch
                    _ui.value = _ui.value.copy(
                        connected = false, connecting = false,
                        reconnecting = true, reconnectAttempt = attempt, reconnectCountdown = left,
                        connectionLabel = "连接断开${reason?.let { ": $it" } ?: ""} · ${left}s 后重连(第 $attempt 次)",
                    )
                    delay(1000)
                }
                if (!isActive || epoch != connectEpoch || userDisconnect) return@launch
                _ui.value = _ui.value.copy(
                    reconnecting = true, reconnectAttempt = attempt, reconnectCountdown = 0,
                    connectionLabel = "正在重连(第 $attempt 次)…",
                )
                closeResources()
                if (attemptConnect(epoch, resetItems = false)) return@launch
                if (!isActive || epoch != connectEpoch || userDisconnect) return@launch
                delaySecs = (delaySecs * 2).coerceAtMost(RECONNECT_MAX_SECS)
            }
        }
    }

    private suspend fun closeResources() {
        runCatching { client?.close() }
        runCatching { ssh?.close() }
        client = null
        ssh = null
    }

    private fun observeClient(rpc: PiRpcClient, epoch: Int) {
        viewModelScope.launch {
            rpc.connection.collect { st ->
                when (st) {
                    is PiRpcClient.ConnectionState.Closed -> {
                        // 手动断开不重连:代次已推进、或用户标记了断开、或这是过期连接
                        if (userDisconnect || epoch != connectEpoch) return@collect
                        // 断开瞬间旧 client 已不可用,清掉引用后进入重连循环
                        client = null
                        ssh = null
                        scheduleReconnect(st.reason)
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            rpc.events.collect { onEvent(it) }
        }
    }

    private fun onEvent(ev: PiEvent) {
        when (ev.type) {
            "extension_ui_request" -> {
                val d = UiDialog(
                    id = ev.raw.str("id") ?: return,
                    method = ev.uiMethod ?: return,
                    title = ev.uiTitle ?: "",
                    message = ev.uiMessage,
                    options = ev.uiOptions,
                    placeholder = ev.uiPlaceholder,
                    prefill = ev.uiPrefill,
                )
                _ui.value = _ui.value.copy(dialog = d)
            }
            "queue_update" -> {
                _ui.value = _ui.value.copy(queueSteering = ev.steeringQueue, queueFollowUp = ev.followUpQueue)
            }
            else -> {
                // 聊天流事件归约
                val newItems = ArrayList<ChatItem>()
                reducer.onEvent(ev) { item ->
                    if (item is StreamReducer.RemoveLive) {
                        _ui.value = _ui.value.copy(
                            items = _ui.value.items.filterNot { it is ChatItem.AssistantText && it.key == "live" },
                        )
                    } else if (item !is ChatItem.SystemNote || item.text.isNotEmpty()) {
                        newItems.add(item)
                    }
                }
                if (newItems.isNotEmpty()) {
                    _ui.value = _ui.value.copy(items = mergeItems(_ui.value.items, newItems))
                }
            }
        }
    }

    /** 合并策略:live 条目替换,tool 卡片按 toolCallId 原地更新,其余追加。*/
    private fun mergeItems(old: List<ChatItem>, fresh: List<ChatItem>): List<ChatItem> {
        val out = old.toMutableList()
        for (item in fresh) {
            when {
                item is ChatItem.AssistantText && item.key == "live" -> {
                    val idx = out.indexOfLast { it is ChatItem.AssistantText && it.key == "live" }
                    if (idx >= 0) out[idx] = item else out.add(item)
                }
                item is ChatItem.ToolCard -> {
                    val idx = out.indexOfLast { it is ChatItem.ToolCard && it.toolCallId == item.toolCallId }
                    if (idx >= 0) {
                        val prev = out[idx] as ChatItem.ToolCard
                        // 输出取最新;args 保留首发值(update 事件不带 args)
                        out[idx] = item.copy(argsSummary = item.argsSummary ?: prev.argsSummary)
                    } else {
                        out.add(item)
                    }
                }
                item is ChatItem.BashOutput -> {
                    val idx = out.indexOfLast { it is ChatItem.BashOutput }
                    if (idx >= 0) {
                        val prev = out[idx] as ChatItem.BashOutput
                        out[idx] = item.copy(command = item.command ?: prev.command, output = prev.output + item.output)
                    } else out.add(item)
                }
                else -> out.add(item)
            }
        }
        return out
    }

    // ---------- 用户动作 ----------

    fun sendPrompt(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || client == null) return
        val isStreaming = _ui.value.state?.isStreaming == true
        val line = if (isStreaming) {
            // 正在流式:默认用 followUp 排队,避免 steering 打断工具执行
            PiCommands.prompt(msg, streamingBehavior = "followUp")
        } else {
            PiCommands.prompt(msg)
        }
        viewModelScope.launch {
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp != null && !resp.success) {
                _ui.value = _ui.value.copy(error = "prompt 被拒绝: ${resp.error}")
            } else {
                _ui.value = _ui.value.copy(items = _ui.value.items + ChatItem.UserText(msg, key = "user-${System.nanoTime()}"))
            }
        }
    }

    fun steer(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            client?.sendLine(PiCommands.named("steer", mapOf("message" to JsonPrimitive(text))))
        }
    }

    fun abort() {
        viewModelScope.launch { client?.sendLine(PiCommands.named("abort")) }
    }

    fun newSession() {
        viewModelScope.launch {
            val id = PiCommands.nextId()
            AppLog.i(TAG, "new_session id=$id")
            val resp = client?.request(PiCommands.named("new_session", id = id), id)
            if (resp?.success == true) {
                AppLog.i(TAG, "new_session ok, cancelled=${resp.data?.get("cancelled")}")
                // 新会话后清掉恢复目标:断线重连就该是新会话,不该回到旧会话
                lastSessionFile = null
                _ui.value = _ui.value.copy(
                    items = listOf(ChatItem.SystemNote("已新建会话", key = "new-session-${System.currentTimeMillis()}")),
                    statsText = null,
                )
                refreshAll()
            } else {
                val msg = "新建会话失败: ${resp?.error ?: "无响应(超时或断开) id=$id"}"
                AppLog.e(TAG, "new_session FAILED: $msg")
                _ui.value = _ui.value.copy(error = msg)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch { refreshState() }
        viewModelScope.launch { refreshModels() }
        viewModelScope.launch { loadHistory() }
        viewModelScope.launch { refreshStats() }
    }

    private suspend fun refreshState() {
        val line = PiCommands.named("get_state")
        val resp = client?.request(line, extractId(line) ?: return)
        AppLog.i(TAG, "get_state -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} err=${resp.error}"}")
        if (resp?.success == true && resp.data != null) {
            val state = PiState.from(JsonObject(mapOf("data" to resp.data!!)))
            // 记住当前会话文件,断线重连时用 --session 恢复
            state.sessionFile?.takeIf { it.isNotBlank() }?.let { lastSessionFile = it }
            _ui.value = _ui.value.copy(state = state)
            // 顺带刷新思考级别
            val tlId = PiCommands.nextId()
            client?.request(PiCommands.named("get_available_thinking_levels", id = tlId), tlId)?.let { r ->
                val levels = (r.data?.get("levels") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (levels != null) _ui.value = _ui.value.copy(thinkingLevels = levels)
            }
        }
    }

    private suspend fun refreshModels() {
        val line = PiCommands.named("get_available_models")
        val resp = client?.request(line, extractId(line) ?: return)
        val models = (resp?.data?.get("models") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { o -> PiModel.from(o) } }
            ?: emptyList()
        AppLog.i(TAG, "get_available_models -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} count=${models.size}"}")
        _ui.value = _ui.value.copy(models = models)
    }

    private suspend fun refreshStats() {
        val id = PiCommands.nextId()
        val resp = client?.request(PiCommands.named("get_session_stats", id = id), id)
        val d = resp?.data ?: return
        val ctx = d["contextUsage"] as? JsonObject
        val tokens = d["tokens"] as? JsonObject
        val text = buildString {
            append("tokens: ")
            append((tokens?.get("total") as? JsonPrimitive)?.contentOrNull ?: "?")
            if (ctx != null) {
                append(" · 上下文 ")
                append((ctx["percent"] as? JsonPrimitive)?.contentOrNull ?: "?")
                append("%")
            }
        }
        _ui.value = _ui.value.copy(statsText = text)
    }

    private suspend fun loadHistory() {
        val id = PiCommands.nextId()
        val resp = client?.request(PiCommands.named("get_messages", id = id), id)
        val msgs = (resp?.data?.get("messages") as? JsonArray)
        AppLog.i(TAG, "get_messages -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} count=${msgs?.size}"}")
        if (msgs == null) return
        val items = ArrayList<ChatItem>()
        for (m in msgs) {
            val obj = m as? JsonObject ?: continue
            val msg = dev.pipilot.app.rpc.ChatMessage.from(obj)
            when (msg.role) {
                "user" -> msg.blocks.firstNotNullOfOrNull { b -> b.text }?.let {
                    items.add(ChatItem.UserText(it, key = "hist-u-${items.size}", timeMs = msg.timestamp ?: 0))
                }
                "assistant" -> {
                    val text = msg.blocks.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
                    val thinking = msg.blocks.filter { it.type == "thinking" }.joinToString("") { it.text ?: "" }
                    if (text.isNotEmpty()) items.add(ChatItem.AssistantText(text, thinking.ifEmpty { null }, false, key = "hist-a-${items.size}", timeMs = msg.timestamp ?: 0))
                    // 工具调用用 toolResult 内容补卡片
                    for (tc in msg.blocks.filter { it.type == "toolCall" }) {
                        items.add(ChatItem.ToolCard(tc.toolCallId ?: "call-${items.size}", tc.toolName ?: "?", tc.argumentsJson, null, running = false, isError = false))
                    }
                }
                "toolResult" -> {
                    val out = msg.blocks.joinToString("") { it.text ?: "" }
                    val toolCallId = (obj["toolCallId"] as? JsonPrimitive)?.contentOrNull
                    val idx = items.indexOfLast { it is ChatItem.ToolCard && it.toolCallId == toolCallId }
                    if (idx >= 0) {
                        val prev = items[idx] as ChatItem.ToolCard
                        items[idx] = prev.copy(output = out, running = false, isError = msg.isError)
                    }
                }
                "bashExecution" -> {
                    val cmd = msg.blocks.firstOrNull()?.toolName
                    val out = msg.blocks.firstOrNull()?.text ?: ""
                    items.add(ChatItem.BashOutput(cmd, out, running = false, key = "hist-bash-${items.size}"))
                }
            }
        }
        // 保留已有的 SystemNote(如"已新建会话"),历史消息追加其后
        val notes = _ui.value.items.filterIsInstance<ChatItem.SystemNote>()
        _ui.value = _ui.value.copy(items = notes + items)
    }

    fun setModel(model: PiModel) {
        viewModelScope.launch {
            val line = PiCommands.setModel(model.provider, model.id)
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp?.success == true) refreshState() else _ui.value = _ui.value.copy(error = resp?.error)
        }
    }

    fun setThinkingLevel(level: String) {
        viewModelScope.launch {
            val line = PiCommands.setThinkingLevel(level)
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp?.success == true) refreshState() else _ui.value = _ui.value.copy(error = resp?.error)
        }
    }

    fun listSessions() {
        val s = settings.value
        viewModelScope.launch {
            try {
                // session 文件可能在子目录(如 --root--/ 下):递归按 mtime 取最近 30 个;
                // 每个文件抓最后一条 session_info 的 name 作为显示名(pi TUI 的命名就存在文件里)
                val out = SshConnector.runQuick(
                    s.toSshConfig(),
                    "find ~/.pi/agent/sessions -name '*.jsonl' -printf '%T@ %p\\n' 2>/dev/null | sort -rn | head -30 | cut -d' ' -f2- | " +
                        "while IFS= read -r p; do " +
                        "n=\\$(grep -h '\"type\":\"session_info\"' \"\\\$p\" 2>/dev/null | tail -1 | " +
                        "sed -n 's/.*\"name\":\"\\([^\"]*\\)\".*/\\1/p'); " +
                        "printf '%s\\t%s\\n' \"\\\$p\" \"\\\$n\"; done",
                )
                val paths = out.lines().filter { it.isNotBlank() }
                AppLog.i(TAG, "listSessions -> ${paths.size} files")
                val entries = paths.map { line ->
                    val path = line.substringBefore('\t')
                    val customName = line.substringAfter('\t', "").takeIf { it.isNotBlank() }
                    SessionEntryInfo(
                        path = path,
                        name = customName ?: path.substringAfterLast('/'),
                        mtime = "",
                    )
                }
                _ui.value = _ui.value.copy(sessions = entries)
            } catch (e: Exception) {
                AppLog.e(TAG, "listSessions FAILED: ${e.message}")
                _ui.value = _ui.value.copy(error = "列会话失败: ${e.message}")
            }
        }
    }

    fun switchSession(path: String) {
        viewModelScope.launch {
            val line = PiCommands.switchSession(path)
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp?.success == true) {
                lastSessionFile = path
                _ui.value = _ui.value.copy(items = emptyList())
                refreshAll()
            } else {
                _ui.value = _ui.value.copy(error = resp?.error)
            }
        }
    }

    /**
     * 删除会话文件。RPC 无 delete 命令,与 pi TUI 的 deleteSessionFile 一致走文件级删除;
     * 当前激活的会话拒绝删除(与 TUI 行为相同)。用单引号包路径防止 shell 元字符注入。
     */
    fun deleteSession(path: String) {
        viewModelScope.launch {
            val s = settings.value
            val active = _ui.value.state?.sessionFile
            if (active != null && pathsEqual(active, path)) {
                _ui.value = _ui.value.copy(error = "不能删除当前会话,请先切换到其他会话")
                return@launch
            }
            try {
                val quoted = "'" + path.replace("'", "'\\''") + "'"
                val out = SshConnector.runQuick(
                    s.toSshConfig(),
                    "rm -f $quoted && [ ! -e $quoted ] && echo DELETED || echo FAILED",
                )
                if (out.contains("DELETED")) {
                    AppLog.i(TAG, "deleteSession OK: $path")
                    _ui.value = _ui.value.copy(sessions = _ui.value.sessions.filter { it.path != path })
                } else {
                    AppLog.e(TAG, "deleteSession FAILED: $path, out=$out")
                    _ui.value = _ui.value.copy(error = "删除失败: $out")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "deleteSession ERROR: ${e.message}")
                _ui.value = _ui.value.copy(error = "删除失败: ${e.message}")
            }
        }
    }

    private fun pathsEqual(a: String, b: String): Boolean {
        if (a == b) return true
        // 容忍尾部差异:sessionFile 可能带或不带扩展名/相对前缀,比对规范化后的绝对路径
        val norm = { p: String -> p.removePrefix("~/").removePrefix("/root/").trimEnd('/') }
        return norm(a) == norm(b)
    }

    fun setSessionName(name: String) {
        viewModelScope.launch {
            val id = PiCommands.nextId()
            val resp = client?.request(PiCommands.setSessionNameReq(name), id)
            if (resp?.success == true) {
                _ui.value = _ui.value.copy(state = _ui.value.state?.copy(sessionName = name.ifBlank { null }))
            } else {
                _ui.value = _ui.value.copy(error = "重命名失败: ${resp?.error ?: "无响应"}")
            }
        }
    }

    fun compact() {
        viewModelScope.launch { client?.sendLine(PiCommands.named("compact")) }
    }

    fun answerDialog(answer: DialogAnswer) {
        val d = _ui.value.dialog ?: return
        val line = when (answer) {
            is DialogAnswer.Value -> PiCommands.uiResponseValue(d.id, answer.value)
            is DialogAnswer.Confirm -> PiCommands.uiResponseConfirm(d.id, answer.confirmed)
            DialogAnswer.Cancel -> PiCommands.uiResponseCancel(d.id)
        }
        viewModelScope.launch {
            runCatching { client?.sendLine(line) }
            _ui.value = _ui.value.copy(dialog = null)
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** 取日志全文(分享用);调用后 logSeq 递增,触发日志弹窗刷新。*/
    fun getLogText(): String = AppLog.dump()

    fun refreshLogView() {
        _ui.value = _ui.value.copy(logSeq = _ui.value.logSeq + 1)
    }

    fun clearLog() {
        AppLog.clear()
        refreshLogView()
    }

    override fun onCleared() {
        connectEpoch++
        userDisconnect = true
        reconnectJob?.cancel()
        super.onCleared()
    }
}

sealed interface DialogAnswer {
    data class Value(val value: String) : DialogAnswer
    data class Confirm(val confirmed: Boolean) : DialogAnswer
    data object Cancel : DialogAnswer
}

private const val RECONNECT_BASE_SECS = 2
private const val RECONNECT_MAX_SECS = 30

private fun ConnectionSettings.toSshConfig(): SshConfig = SshConfig(
    host = host,
    port = port.toIntOrNull() ?: 22,
    user = user,
    auth = if (authType == "key") SshConfig.Auth.PrivateKey(privateKey, keyPassphrase.ifBlank { null })
    else SshConfig.Auth.Password(password),
)

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 从序列化命令 JSON 里拿回 id,避免 PiCommands 暴露内部状态。*/
private fun extractId(line: String): String? = try {
    (piJson.parseToJsonElement(line).jsonObject["id"] as? JsonPrimitive)?.contentOrNull
} catch (_: Exception) {
    null
}

private val TAG = "PiViewModel"
