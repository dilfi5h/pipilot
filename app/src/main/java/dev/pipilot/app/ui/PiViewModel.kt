package dev.pipilot.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pipilot.app.chat.ChatItem
import dev.pipilot.app.chat.StreamReducer
import dev.pipilot.app.keepalive.ConnectionKeepAliveService
import dev.pipilot.app.log.AppLog
import dev.pipilot.app.rpc.PiCommands
import dev.pipilot.app.rpc.PiEvent
import dev.pipilot.app.rpc.PiModel
import dev.pipilot.app.rpc.filterEnabledModels
import dev.pipilot.app.rpc.PiResponse
import dev.pipilot.app.rpc.PiRpcClient
import dev.pipilot.app.rpc.PiState
import dev.pipilot.app.rpc.piJson
import dev.pipilot.app.settings.ConnectionSettings
import dev.pipilot.app.settings.HostProfilesState
import dev.pipilot.app.settings.HostProfileStore
import dev.pipilot.app.settings.SettingsStore
import dev.pipilot.app.ssh.SshConfig
import dev.pipilot.app.ssh.SshConnector
import dev.pipilot.app.ssh.SshExecSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val sessionsLoading: Boolean = false,
    val sessionsDir: String? = null,
    val queueSteering: List<String> = emptyList(),
    val queueFollowUp: List<String> = emptyList(),
    val dialog: UiDialog? = null,
    val statsText: String? = null,
)

class PiViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<ConnectionSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSettings())

    private val hostProfiles = HostProfileStore(settingsStore)
    val profiles: StateFlow<HostProfilesState> =
        hostProfiles.state.stateIn(viewModelScope, SharingStarted.Eagerly, HostProfilesState())

    /** 当前生效的连接配置:多主机选中项,回退到老单配置。 */
    val activeSettings: StateFlow<ConnectionSettings> = kotlinx.coroutines.flow.combine(
        profiles, settings,
    ) { p, legacy -> p.active.takeIf { it.isValid } ?: legacy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---------- 网络变化 & 应用级心跳 ----------

    /** 默认网络回调:换网/断网时 Android 会悄悄废掉 socket,不等 readLoop 报错就该知情。*/
    private val connectivity: ConnectivityManager? =
        app.getSystemService(ConnectivityManager::class.java)
    private var heartbeatJob: Job? = null
    private var observeJobs: List<Job> = emptyList()

    /** 是否在后台(由 PiScreen 生命周期维护):后台心跳加密,前台放宽省电。*/
    @Volatile private var backgrounded = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            when {
                _ui.value.reconnecting -> {
                    AppLog.i(TAG, "network available -> reconnect now")
                    reconnectNow("网络已恢复")
                }
                _ui.value.connected -> {
                    AppLog.i(TAG, "network available -> probe")
                    probeAndMaybeReconnect("网络已恢复")
                }
                else -> AppLog.i(TAG, "network available (idle)")
            }
        }

        override fun onLost(network: Network) {
            AppLog.w(TAG, "network lost (connected=${_ui.value.connected}, reconnecting=${_ui.value.reconnecting})")
            // 这条 socket 已随网络一起死:立刻拆掉,别等 readLoop/心跳超时
            if (!userDisconnect && _ui.value.connected) forceReconnect("网络断开")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                AppLog.w(TAG, "network not validated (no internet)")
            }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess {
                AppLog.i(TAG, "network watch registered")
            }
            .onFailure { AppLog.w(TAG, "network watch failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private var ssh: SshExecSession? = null
    private var client: PiRpcClient? = null
    private val reducer = StreamReducer()

    // 最近一次已连接会话的 session 文件;重连成功后用 `pi --mode rpc --session <path>` 恢复,
    // 避免"断了丢现场"(新起的 pi 进程默认是空会话)
    private var lastSessionFile: String? = null
    /** 当前会话已同步到的最后一个 append-only entry id。*/
    private var lastEntryId: String? = null

    /** 本地维护的流式状态:get_state 快照会滞后,agent_start/end 才是即时信号。*/
    @Volatile private var locallyStreaming = false

    // 重连控制:connectEpoch 隔离过期循环,userDisconnect 标记手动断开
    private var connectEpoch = 0
    @Volatile private var userDisconnect = false
    private var reconnectJob: Job? = null
    /** 最近一次成功建连的时刻;用于识别"连上即退"的启动失败(不进重连循环)。*/
    private var connectedAtMs = 0L

    fun saveSettings(s: ConnectionSettings) {
        viewModelScope.launch { settingsStore.save(s) }
    }

    /** 保存当前编辑的主机配置(新建或覆盖同名),并设为当前选中。 */
    fun saveProfile(name: String, s: ConnectionSettings) {
        val profileName = name.trim().ifBlank { "default" }
        viewModelScope.launch {
            hostProfiles.saveProfile(profileName, s)
            // 老单配置同步一份,保持旧读取路径可用
            settingsStore.save(s)
        }
    }

    fun setActiveProfile(name: String) {
        viewModelScope.launch { hostProfiles.setActive(name) }
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch { hostProfiles.deleteProfile(name) }
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
     * @param resetItems 成功后是否清空聊天列表(手动连接清空,重连也清空后由 loadHistory 全量重建,
     *   避免增量同步把直播渲染过的消息再 append 一遍)。
     */
    private suspend fun attemptConnect(epoch: Int, resetItems: Boolean = true): Boolean {
        val s = activeSettings.value
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
            val rpc = PiRpcClient(
                exec.stdin, exec.stdout, exec.stderr,
                // 阻塞等远端命令退出:pi 常驻时不返回;启动即退(没装 pi 等)时拿到 exit 码做诊断
                remoteExit = {
                    runCatching { exec.command.join() }
                    runCatching { exec.command.exitStatus }.getOrNull()
                },
            )
            client = rpc
            try {
                rpc.start()
                observeClient(rpc, epoch)
                // 应用级心跳:SSH 传输层的心跳只能证明 SSH 活着,证明不了这个 socket 还有下行;
                // 后台被系统收走网络时,readLoop 可能既不报错也不返回,必须自己打探
                restartHeartbeat()
                connectedAtMs = System.currentTimeMillis()
                // 重连也全量重建:直播消息不推进游标,增量同步会把已上屏的消息重复 append
                if (!resetItems) lastEntryId = null
                _ui.value = _ui.value.copy(
                    connected = true, connecting = false,
                    reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                    connectionLabel = "已连接",
                    items = emptyList(),
                )
                refreshAll()
                AppLog.i(TAG, "connect ok${if (!resetItems) " (resumed $lastSessionFile)" else ""}")
                true
            } catch (inner: Exception) {
                runCatching { rpc.close(notify = false) }
                runCatching { exec.close() }
                if (client === rpc) client = null
                if (ssh === exec) ssh = null
                throw inner
            }
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
            closeResources()
            if (resetItems) lastEntryId = null
            _ui.value = _ui.value.copy(
                connecting = true, error = null,
                reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                items = if (resetItems) emptyList() else _ui.value.items,
                connectionLabel = "连接中…",
            )
            val ok = attemptConnect(epoch, resetItems = resetItems)
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
    private fun scheduleReconnect(reason: String?, immediate: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val epoch = ++connectEpoch
            var delaySecs = if (immediate) 0 else RECONNECT_BASE_SECS
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
                        // 重连期间保留聊天:banner 叠在列表上,不要把用户踢回空连接页
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
                delaySecs = if (delaySecs == 0) RECONNECT_BASE_SECS else (delaySecs * 2).coerceAtMost(RECONNECT_MAX_SECS)
            }
        }
    }

    private suspend fun closeResources() {
        stopHeartbeat()
        stopObserving()
        locallyStreaming = false
        val rpc = client
        val session = ssh
        client = null
        ssh = null
        runCatching { rpc?.close(notify = false) }
        runCatching { session?.close() }
    }

    private fun stopObserving() {
        observeJobs.forEach { it.cancel() }
        observeJobs = emptyList()
    }

    private fun observeClient(rpc: PiRpcClient, epoch: Int) {
        stopObserving()
        val connJob = viewModelScope.launch {
            rpc.connection.collect { st ->
                when (st) {
                    is PiRpcClient.ConnectionState.Closed -> {
                        // 手动断开 / 本地拆除 / 过期代次都不重连
                        if (userDisconnect || epoch != connectEpoch || st.reason == "closed locally") {
                            AppLog.i(TAG, "Closed ignored (userDisconnect=$userDisconnect epoch=$epoch/$connectEpoch): ${st.reason}")
                            return@collect
                        }
                        AppLog.i(TAG, "connection Closed -> reconnect path: ${st.reason}")
                        val reason = st.reason
                        val fastFatal = reason?.startsWith("远程命令退出") == true &&
                            System.currentTimeMillis() - connectedAtMs < 10_000
                        viewModelScope.launch {
                            closeResources()
                            if (userDisconnect || epoch != connectEpoch) return@launch
                            if (fastFatal) {
                                reconnectJob?.cancel()
                                AppLog.e(TAG, "remote command exited right after connect; stop reconnect: $reason")
                                _ui.value = _ui.value.copy(
                                    connected = false, connecting = false,
                                    reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                                    connectionLabel = "未连接", error = reason,
                                )
                            } else {
                                scheduleReconnect(reason)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        val evJob = viewModelScope.launch {
            rpc.events.collect { onEvent(it) }
        }
        observeJobs = listOf(connJob, evJob)
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
            "agent_start" -> {
                locallyStreaming = true
                _ui.value = _ui.value.copy(state = _ui.value.state?.copy(isStreaming = true))
                reduceChat(ev)
            }
            "agent_end", "agent_settled" -> {
                locallyStreaming = false
                _ui.value = _ui.value.copy(state = _ui.value.state?.copy(isStreaming = false))
                reduceChat(ev)
            }
            else -> reduceChat(ev)
        }
    }

    private fun reduceChat(ev: PiEvent) {
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
                    val idx = out.indexOfLast { it is ChatItem.BashOutput && it.key == item.key }
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

    fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList()) {
        val msg = text.trim()
        if ((msg.isEmpty() && images.isEmpty()) || client == null) return
        val isStreaming = locallyStreaming || _ui.value.state?.isStreaming == true
        val line = if (isStreaming) {
            // 正在流式:默认用 followUp 排队,避免 steering 打断工具执行
            PiCommands.prompt(msg, streamingBehavior = "followUp", images = images)
        } else {
            PiCommands.prompt(msg, images = images)
        }
        viewModelScope.launch {
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp != null && !resp.success) {
                _ui.value = _ui.value.copy(error = "prompt 被拒绝: ${resp.error}")
            } else {
                val label = if (msg.isNotEmpty()) msg else "（已发送 ${images.size} 张图片）"
                _ui.value = _ui.value.copy(items = _ui.value.items + ChatItem.UserText(label, key = "user-${System.nanoTime()}", imageCount = images.size))
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
                lastEntryId = null
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

    /**
     * 回前台 / 网络恢复时主动探活:后台冻结期间 socket 可能已死,但 readLoop 要等解冻才报 Closed。
     * 轻量 get_state;超时且期间没有任何下行才算死,随后拆连接并进重连循环,避免 UI 一直显示"已连接"。
     * 已在重连中则跳过。
     */
    fun onForegroundResume() = probeAndMaybeReconnect("回前台")

    private fun probeAndMaybeReconnect(reason: String) {
        val snap = _ui.value
        if (!snap.connected || snap.reconnecting || snap.connecting || userDisconnect) return
        val rpc = client ?: return
        viewModelScope.launch {
            AppLog.i(TAG, "probe get_state ($reason)")
            val id = PiCommands.nextId()
            val sentAt = System.currentTimeMillis()
            val resp = rpc.request(PiCommands.named("get_state", id = id), id, timeoutMs = RESUME_PROBE_TIMEOUT_MS)
            if (resp != null) {
                AppLog.i(TAG, "probe ok ($reason) success=${resp.success}")
                return@launch
            }
            // 等待期间可能已手动断开 / 另开连接 / 已进入重连
            if (userDisconnect || client !== rpc || !_ui.value.connected) {
                AppLog.i(TAG, "probe TIMEOUT ignored (state changed)")
                return@launch
            }
            // 超时期间只要有下行(响应/事件/stderr)就算活着:agent 长工具调用会把命令响应拖住,
            // 误判重连会把正在跑的 agent 掐死
            if (rpc.lastInboundAtMs > sentAt) {
                AppLog.w(TAG, "probe TIMEOUT but inbound traffic seen ($reason); keep connection")
                return@launch
            }
            AppLog.w(TAG, "probe TIMEOUT ($reason); forcing reconnect")
            forceReconnect(reason)
        }
    }

    /** 主动拆掉当前连接并进入重连循环(探活超时 / 网络变化用)。*/
    private fun forceReconnect(reason: String) {
        viewModelScope.launch {
            closeResources()
            if (userDisconnect) return@launch
            scheduleReconnect(reason)
        }
    }

    /** 立刻重试(不退避):网络刚恢复时用,退避只适用于"远端还没好"的场景。*/
    private fun reconnectNow(reason: String) {
        if (userDisconnect) return
        AppLog.i(TAG, "reconnect now ($reason)")
        scheduleReconnect(reason, immediate = true)
    }

    /** PiScreen 生命周期驱动:后台心跳加密(尽快发现 socket 被系统收走),前台放宽省电。*/
    fun setBackgrounded(value: Boolean) {
        if (backgrounded == value) return
        backgrounded = value
        AppLog.i(
            TAG,
            "app ${if (value) "background" else "foreground"}; heartbeat=${if (value) HEARTBEAT_BG_MS / 1000 else HEARTBEAT_FG_MS / 1000}s",
        )
        if (client != null) restartHeartbeat()
    }

    /**
     * 应用级心跳。为什么不能只靠 SSH 层心跳:传输层心跳只证明 SSH 链路能收发包,
     * 而且 SO_TIMEOUT=0 时死 socket 会一直阻塞在 read,既不报错也不返回 —— 只有主动打探 + 看下行
     * 才能分清"链路真死了"和"agent 正在长工具调用没空回包"。
     */
    private fun restartHeartbeat() {
        val rpc = client ?: return
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (isActive && client === rpc && !userDisconnect) {
                val interval = if (backgrounded) HEARTBEAT_BG_MS else HEARTBEAT_FG_MS
                delay(interval)
                if (client !== rpc) return@launch
                // 最近有下行 → 链路活着,不用打探
                if (System.currentTimeMillis() - rpc.lastInboundAtMs < interval) continue
                val id = PiCommands.nextId()
                val sentAt = System.currentTimeMillis()
                val resp = rpc.request(PiCommands.named("get_state", id = id), id, timeoutMs = HEARTBEAT_TIMEOUT_MS)
                if (resp != null) {
                    AppLog.d(TAG, "heartbeat ok (${System.currentTimeMillis() - sentAt}ms)")
                    continue
                }
                if (rpc.lastInboundAtMs > sentAt) {
                    AppLog.w(TAG, "heartbeat TIMEOUT but inbound traffic seen; keep connection")
                    continue
                }
                AppLog.w(TAG, "heartbeat TIMEOUT, no inbound since ${sentAt}; forcing reconnect")
                forceReconnect("心跳超时")
                return@launch
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun refreshState() {
        val line = PiCommands.named("get_state")
        val resp = client?.request(line, extractId(line) ?: return)
        // 例行刷新用 D(不落盘);TIMEOUT / success=false 才算证据
        if (resp?.success == true) AppLog.d(TAG, "get_state -> success=true err=null")
        else AppLog.w(TAG, "get_state -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} err=${resp.error}"}")
        if (resp?.success == true && resp.data != null) {
            val state = PiState.from(JsonObject(mapOf("data" to resp.data!!)))
            // 记住当前会话文件,断线重连时用 --session 恢复
            state.sessionFile?.takeIf { it.isNotBlank() }?.let { lastSessionFile = it }
            locallyStreaming = state.isStreaming
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
        val available = (resp?.data?.get("models") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { o -> PiModel.from(o) } }
            ?: emptyList()
        val patterns = readEnabledModelPatterns()
        val models = filterEnabledModels(available, patterns)
        val detail = "get_available_models -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} available=${available.size} filtered=${models.size} patterns=${patterns?.size ?: 0}"}"
        if (resp?.success == true) AppLog.d(TAG, detail) else AppLog.w(TAG, detail)
        _ui.value = _ui.value.copy(models = models)
    }

    /** Read global and project pi settings; project enabledModels overrides global. */
    private suspend fun readEnabledModelPatterns(): List<String>? {
        val s = activeSettings.value
        return runCatching {
            val projectSettings = s.workDir.trimEnd('/') + "/.pi/settings.json"
            val cmd = "for f in \"\${PI_CODING_AGENT_DIR:-\$HOME/.pi/agent}/settings.json\" ${shellQuote(projectSettings)}; do [ -f \"\$f\" ] && printf '%s\\n' \"\$f\"; done"
            val files = SshConnector.runQuick(s.toSshConfig(), cmd).lines().filter { it.isNotBlank() }
            var selected: List<String>? = null
            for (file in files) {
                val text = SshConnector.runQuick(s.toSshConfig(), "cat ${shellQuote(file)}")
                val obj = runCatching { piJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val arr = obj["enabledModels"] as? JsonArray
                if (arr != null) selected = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
            selected
        }.getOrElse {
            AppLog.e(TAG, "read enabledModels failed: ${it.javaClass.simpleName}")
            null
        }
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
                append(formatPercent((ctx["percent"] as? JsonPrimitive)?.contentOrNull))
                append("%")
            }
        }
        _ui.value = _ui.value.copy(statsText = text)
    }

    /** 上下文百分比最多保留两位小数;整数不带尾零(30 / 12.5 / 12.34)。*/
    private fun formatPercent(raw: String?): String {
        if (raw.isNullOrBlank()) return "?"
        val n = raw.toDoubleOrNull() ?: return raw
        val s = String.format(java.util.Locale.US, "%.2f", n)
        return s.trimEnd('0').trimEnd('.')
    }

    private suspend fun loadHistory() {
        var since = lastEntryId
        val id = PiCommands.nextId()
        var resp = client?.request(PiCommands.getEntries(since, id), id)
        // 服务端找不到游标时,退回全量同步并重置本地历史;since 必须一并置空,
        // 否则全量快照会 merge 到整个旧列表上,整段对话显示两遍
        if (resp?.success != true && since != null) {
            AppLog.w(TAG, "get_entries since=$since failed; retrying full")
            lastEntryId = null
            since = null
            val retryId = PiCommands.nextId()
            resp = client?.request(PiCommands.getEntries(id = retryId), retryId)
        }
        val entries = (resp?.data?.get("entries") as? JsonArray)
        val histDetail = "get_entries -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} since=${since ?: "<full>"} count=${entries?.size}"}"
        if (resp?.success == true) AppLog.d(TAG, histDetail) else AppLog.w(TAG, histDetail)
        if (entries == null || resp?.success != true) return
        val items = ArrayList<ChatItem>()
        for (entryElement in entries) {
            val entry = entryElement as? JsonObject ?: continue
            entry.str("id")?.takeIf { it.isNotBlank() }?.let { lastEntryId = it }
            val obj = (entry["message"] as? JsonObject) ?: continue
            val msg = dev.pipilot.app.rpc.ChatMessage.from(obj)
            when (msg.role) {
                "user" -> {
                    val text = msg.blocks.firstNotNullOfOrNull { b -> b.text }.orEmpty()
                    val imageCount = msg.blocks.count { it.type == "image" }
                    if (text.isNotBlank() || imageCount > 0) {
                        items.add(ChatItem.UserText(text.ifBlank { "（${imageCount} 张图片）" }, key = "hist-u-${entry.str("id") ?: items.size}", timeMs = msg.timestamp ?: 0, imageCount = imageCount))
                    }
                }
                "assistant" -> {
                    val text = msg.blocks.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
                    val thinking = msg.blocks.filter { it.type == "thinking" }.joinToString("") { it.text ?: "" }
                    if (text.isNotEmpty()) items.add(ChatItem.AssistantText(text, thinking.ifEmpty { null }, false, key = "hist-a-${entry.str("id") ?: items.size}", timeMs = msg.timestamp ?: 0))
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
                    items.add(ChatItem.BashOutput(cmd, out, running = false, key = "hist-bash-${entry.str("id") ?: items.size}"))
                }
            }
        }
        val old = _ui.value.items
        val base = if (since == null) old.filterIsInstance<ChatItem.SystemNote>() else old
        _ui.value = _ui.value.copy(items = if (since == null) base + items else mergeItems(base, items))
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
        val s = activeSettings.value
        if (_ui.value.sessionsLoading) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(sessionsLoading = true)
            try {
                // 当前激活会话所在目录:兜底覆盖 --session-dir 等自定义目录场景
                val fallbackDir = _ui.value.state?.sessionFile
                    ?.takeIf { it.isNotBlank() }
                    ?.substringBeforeLast('/', "")
                    ?.takeIf { it.isNotBlank() }
                // 可移植列会话:不用 GNU find -printf(远端可能是 BusyBox/BSD find),
                // 目录取 PI_CODING_AGENT_DIR/PI_CODING_AGENT_SESSION_DIR(与上游 getAgentDir/getSessionsDir 一致),
                // mtime 用 stat 双写法(GNU -c %Y / BSD -f %m)兼容,排序后取最近 30 个;
                // 每个文件抓最后一条 session_info 的 name 作为显示名(pi TUI 的命名就存在文件里)
                val listFn =
                    "lsSessions() { d=\"\$1\"; [ -d \"\$d\" ] || return 0; " +
                        "find \"\$d\" -type f -name '*.jsonl' 2>/dev/null | " +
                        "while IFS= read -r p; do " +
                        "mt=\$(stat -c %Y \"\$p\" 2>/dev/null || stat -f %m \"\$p\" 2>/dev/null || echo 0); " +
                        "mt=\${mt%%[!0-9]*}; [ -z \"\$mt\" ] && mt=0; " +
                        "printf '%s %s\\n' \"\$mt\" \"\$p\"; done | " +
                        "sort -rn | head -30 | cut -d' ' -f2- | " +
                        "while IFS= read -r p; do " +
                        "n=\$(grep -h '\"type\":\"session_info\"' \"\$p\" 2>/dev/null | tail -1 | " +
                        "sed -n 's/.*\"name\":\"\\([^\"]*\\)\".*/\\1/p'); " +
                        "printf '%s\\t%s\\n' \"\$p\" \"\$n\"; done; }; " +
                        "agentdir=\${PI_CODING_AGENT_DIR:-\$HOME/.pi/agent}; " +
                        "sessdir=\${PI_CODING_AGENT_SESSION_DIR:-\$agentdir/sessions}; " +
                        "echo \"SESSDIR:\$sessdir\"; lsSessions \"\$sessdir\"" +
                        (fallbackDir?.let { "; fbd=${shellQuote(it)}; if [ -n \"\$fbd\" ] && [ \"\$fbd\" != \"\$sessdir\" ]; then lsSessions \"\$fbd\"; fi" } ?: "")
                val out = SshConnector.runQuick(s.toSshConfig(), listFn)
                val rawLines = out.lines()
                val sessDir = rawLines.firstOrNull { it.startsWith("SESSDIR:") }
                    ?.removePrefix("SESSDIR:")?.trim()?.takeIf { it.isNotBlank() }
                val paths = rawLines.filter { it.isNotBlank() && !it.startsWith("SESSDIR:") }
                AppLog.i(TAG, "listSessions dir=$sessDir -> ${paths.size} files")
                val entries = paths.map { line ->
                    val path = line.substringBefore('\t')
                    val customName = line.substringAfter('\t', "").takeIf { it.isNotBlank() }
                    SessionEntryInfo(
                        path = path,
                        name = customName ?: path.substringAfterLast('/'),
                        mtime = "",
                    )
                }.distinctBy { it.path }
                _ui.value = _ui.value.copy(sessions = entries, sessionsDir = sessDir)
            } catch (e: Exception) {
                AppLog.e(TAG, "listSessions FAILED: ${e.message}")
                _ui.value = _ui.value.copy(error = "列会话失败: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(sessionsLoading = false)
            }
        }
    }

    fun switchSession(path: String) {
        viewModelScope.launch {
            val line = PiCommands.switchSession(path)
            val resp = client?.request(line, extractId(line) ?: return@launch)
            if (resp?.success == true) {
                lastSessionFile = path
                lastEntryId = null
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
            val s = activeSettings.value
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
            // 必须把同一个 id 写进请求体;否则 waiter 等 A、服务端回 B → UNMATCHED + 假 TIMEOUT
            val resp = client?.request(PiCommands.setSessionNameReq(name, id = id), id)
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
        stopHeartbeat()
        stopObserving()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        // Activity 真正销毁时关掉套接字,避免 SSH/RPC 泄漏到进程退出。
        // 关闭会阻塞到 socket 收尾,放独立 IO 作用域异步做,不在主线程 runBlocking(有 ANR 面)
        val closingClient = client
        val closingSsh = ssh
        client = null
        ssh = null
        if (closingClient != null || closingSsh != null) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                runCatching { closingClient?.close(notify = false) }
                runCatching { closingSsh?.close() }
            }
        }
        // 连接已随 ViewModel 关闭,保活服务失去意义:立刻停掉,
        // 否则 FGS 会持 Wakelock/WifiLock 空转到 10 分钟窗口结束(stopWithTask=false 时还会活过划掉 App)
        runCatching { ConnectionKeepAliveService.stop(getApplication()) }
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

/** 后台心跳间隔:切后台后加密,尽快发现"系统把 socket 收走了"。*/
private const val HEARTBEAT_BG_MS = 20_000L
/** 前台心跳间隔:链路有 RPC 流量时本来就会续命,这里只做兜底,省电优先。*/
private const val HEARTBEAT_FG_MS = 60_000L
/** 心跳/探活的 get_state 超时;超时后还要看期间有没有任何下行才算死。*/
private const val HEARTBEAT_TIMEOUT_MS = 10_000L
private const val RESUME_PROBE_TIMEOUT_MS = 8_000L

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
