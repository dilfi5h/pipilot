package dev.pipilot.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pipilot.app.chat.ChatCollapse
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
import dev.pipilot.app.rpc.joinQueueDraft
import dev.pipilot.app.rpc.str
import dev.pipilot.app.rpc.stringList

/** Extension UI request (select/confirm/input), mapped to an Android dialog. */
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
    val connectionLabel: String = "Disconnected",
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
    /** abort/clear_queue restore unsent queue text into the composer; UI should call consumeRestoreDraft after applying. */
    val restoreDraft: String? = null,
    val dialog: UiDialog? = null,
    val statsText: String? = null,
    /** Bumped after a full history rebuild so ChatList re-pins to the latest output. */
    val historyEpoch: Long = 0,
)

class PiViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    val settings: StateFlow<ConnectionSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSettings())

    private val hostProfiles = HostProfileStore(settingsStore)
    val profiles: StateFlow<HostProfilesState> =
        hostProfiles.state.stateIn(viewModelScope, SharingStarted.Eagerly, HostProfilesState())

    /** Active connection settings: selected multi-host profile, falling back to the legacy single config. */
    val activeSettings: StateFlow<ConnectionSettings> = kotlinx.coroutines.flow.combine(
        profiles, settings,
    ) { p, legacy -> p.active.takeIf { it.isValid } ?: legacy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionSettings())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---------- Network changes & app-level heartbeat ----------

    /** Default network callback: Android silently kills the socket on network change/loss, so learn before readLoop errors. */
    private val connectivity: ConnectivityManager? =
        app.getSystemService(ConnectivityManager::class.java)
    private var heartbeatJob: Job? = null
    private var observeJobs: List<Job> = emptyList()

    /** Backgrounded flag (owned by PiScreen lifecycle): tighten heartbeat in background, relax in foreground to save power. */
    @Volatile private var backgrounded = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            when {
                _ui.value.reconnecting -> {
                    AppLog.i(TAG, "network available -> reconnect now")
                    reconnectNow("network restored")
                }
                _ui.value.connected -> {
                    AppLog.i(TAG, "network available -> probe")
                    probeAndMaybeReconnect("network restored")
                }
                else -> AppLog.i(TAG, "network available (idle)")
            }
        }

        override fun onLost(network: Network) {
            AppLog.w(TAG, "network lost (connected=${_ui.value.connected}, reconnecting=${_ui.value.reconnecting})")
            // This socket died with the network: tear it down now; do not wait for readLoop/heartbeat timeout
            if (!userDisconnect && _ui.value.connected) forceReconnect("network lost")
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

    // Last connected session file; after a successful reconnect resume with `pi --mode rpc --session <path>`
    // so we do not lose context (a fresh pi process starts an empty session)
    private var lastSessionFile: String? = null
    /** Last append-only entry id synced for the current session. */
    private var lastEntryId: String? = null

    /** Local streaming flag: get_state snapshots lag; agent_start/end are the live signals. */
    @Volatile private var locallyStreaming = false

    // Reconnect control: connectEpoch isolates stale loops; userDisconnect marks a manual disconnect
    private var connectEpoch = 0
    @Volatile private var userDisconnect = false
    private var reconnectJob: Job? = null
    /** Time of the last successful connect; used to detect "exited right after connect" (skip reconnect loop). */
    private var connectedAtMs = 0L

    fun saveSettings(s: ConnectionSettings) {
        viewModelScope.launch { settingsStore.save(s) }
    }

    /** Save the host profile being edited (create or overwrite same name) and select it. */
    fun saveProfile(name: String, s: ConnectionSettings) {
        val profileName = name.trim().ifBlank { "default" }
        viewModelScope.launch {
            hostProfiles.saveProfile(profileName, s)
            // Also sync the legacy single config so the old read path still works
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
     * One connect attempt. Returns true on success.
     * @param epoch generation for this attempt; reconnect and manual connect each take a generation; stale results are dropped.
     * @param resetItems whether to clear the chat list on success (manual connect clears; reconnect also clears then loadHistory rebuilds fully,
     *   so incremental sync does not append live-rendered messages again).
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
            // On reconnect (resetItems=false) start with the last session path to resume; manual connect always starts fresh
            val resumeArg = if (!resetItems) lastSessionFile?.let { " --session ${shellQuote(it)}" } ?: "" else ""
            val exec = SshConnector.connectAndExec(
                SshConfig(s.host, s.port.toIntOrNull() ?: 22, s.user, auth),
                "${cd}${s.piCommand}$resumeArg",
            )
            if (epoch != connectEpoch) {
                // Stale: close the just-built connection and discard it
                runCatching { exec.close() }
                return false
            }
            ssh = exec
            val rpc = PiRpcClient(
                exec.stdin, exec.stdout, exec.stderr,
                // Block for remote exit: a resident pi never returns; an immediate exit (pi missing, etc.) yields an exit code for diagnosis
                remoteExit = {
                    runCatching { exec.command.join() }
                    runCatching { exec.command.exitStatus }.getOrNull()
                },
            )
            client = rpc
            try {
                rpc.start()
                observeClient(rpc, epoch)
                // App-level heartbeat: SSH transport keepalive only proves SSH is alive, not that this socket still has inbound traffic;
                // when the OS reclaims the network in the background, readLoop may neither error nor return, so we must probe ourselves
                restartHeartbeat()
                connectedAtMs = System.currentTimeMillis()
                // Reconnect also rebuilds fully: live messages do not advance the cursor, so incremental sync would append already-shown messages again
                if (!resetItems) lastEntryId = null
                _ui.value = _ui.value.copy(
                    connected = true, connecting = false,
                    reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                    connectionLabel = "Connected",
                    items = emptyList(),
                    queueSteering = emptyList(),
                    queueFollowUp = emptyList(),
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
                // Failures during reconnect do not set error: the countdown banner already explains it, avoid a Snackbar on every retry
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
                connectionLabel = "Connecting…",
            )
            val ok = attemptConnect(epoch, resetItems = resetItems)
            if (!ok && epoch == connectEpoch) {
                _ui.value = _ui.value.copy(connected = false, connecting = false, connectionLabel = "Disconnected")
            }
        }
    }

    fun disconnect() {
        // Manual disconnect: bump the generation so in-flight connect/reconnect attempts become stale
        connectEpoch++
        userDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        viewModelScope.launch {
            closeResources()
            _ui.value = _ui.value.copy(
                connected = false, connecting = false,
                reconnecting = false, reconnectAttempt = 0, reconnectCountdown = 0,
                connectionLabel = "Disconnected", dialog = null,
                queueSteering = emptyList(), queueFollowUp = emptyList(),
            )
        }
    }

    /** User taps Cancel on the reconnect banner: same as a manual disconnect; stop retrying. */
    fun cancelReconnect() = disconnect()

    /**
     * Auto-reconnect loop after an unexpected drop: exponential backoff 2s → 4s → 8s … capped at 30s,
     * retry forever until success, manual disconnect/cancel, or ViewModel destruction. Refresh the countdown UI every second while waiting.
     */
    private fun scheduleReconnect(reason: String?, immediate: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val epoch = ++connectEpoch
            var delaySecs = if (immediate) 0 else RECONNECT_BASE_SECS
            var attempt = 0
            while (isActive && epoch == connectEpoch && !userDisconnect) {
                attempt++
                // Countdown (can be cancelled)
                for (left in delaySecs downTo 1) {
                    if (!isActive || epoch != connectEpoch || userDisconnect) return@launch
                    _ui.value = _ui.value.copy(
                        connected = false, connecting = false,
                        reconnecting = true, reconnectAttempt = attempt, reconnectCountdown = left,
                        connectionLabel = "Disconnected${reason?.let { ": $it" } ?: ""} · reconnecting in ${left}s (attempt $attempt)",
                        // Keep the chat during reconnect: banner overlays the list; do not kick the user back to the empty connect page
                    )
                    delay(1000)
                }
                if (!isActive || epoch != connectEpoch || userDisconnect) return@launch
                _ui.value = _ui.value.copy(
                    reconnecting = true, reconnectAttempt = attempt, reconnectCountdown = 0,
                    connectionLabel = "Reconnecting (attempt $attempt)…",
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
                        // Manual disconnect / local teardown / stale generation do not reconnect
                        if (userDisconnect || epoch != connectEpoch || st.reason == "closed locally") {
                            AppLog.i(TAG, "Closed ignored (userDisconnect=$userDisconnect epoch=$epoch/$connectEpoch): ${st.reason}")
                            return@collect
                        }
                        AppLog.i(TAG, "connection Closed -> reconnect path: ${st.reason}")
                        val reason = st.reason
                        val fastFatal = reason?.startsWith("Remote command exited") == true &&
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
                                    connectionLabel = "Disconnected", error = reason,
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

    /** Merge strategy: replace live items, update tool cards in place by toolCallId, append the rest. */
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
                        // Take the latest output; keep the first args (update events omit args)
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

    // ---------- User actions ----------

    fun sendPrompt(text: String, images: List<Pair<String, String>> = emptyList()) {
        enqueueUserMessage(text, images, whileStreaming = StreamingSend.FollowUp)
    }

    /** Steer while streaming: delivered after current tools finish, before the next LLM call. Falls back to a normal prompt when idle. */
    fun sendSteer(text: String, images: List<Pair<String, String>> = emptyList()) {
        enqueueUserMessage(text, images, whileStreaming = StreamingSend.Steer)
    }

    /** Queue a follow-up while streaming: processed after the agent fully settles. Falls back to a normal prompt when idle. */
    fun sendFollowUp(text: String, images: List<Pair<String, String>> = emptyList()) {
        enqueueUserMessage(text, images, whileStreaming = StreamingSend.FollowUp)
    }

    private enum class StreamingSend { Steer, FollowUp }

    private fun enqueueUserMessage(
        text: String,
        images: List<Pair<String, String>>,
        whileStreaming: StreamingSend,
    ) {
        val msg = text.trim()
        if ((msg.isEmpty() && images.isEmpty()) || client == null) return
        val streaming = locallyStreaming || _ui.value.state?.isStreaming == true
        val id = PiCommands.nextId()
        val line = when {
            !streaming -> PiCommands.prompt(msg, images = images, id = id)
            whileStreaming == StreamingSend.Steer -> PiCommands.steer(msg, images = images, id = id)
            else -> PiCommands.followUp(msg, images = images, id = id)
        }
        val rejected = when {
            !streaming -> "prompt rejected"
            whileStreaming == StreamingSend.Steer -> "steer rejected"
            else -> "follow-up rejected"
        }
        viewModelScope.launch {
            val resp = client?.request(line, id)
            if (resp != null && !resp.success) {
                _ui.value = _ui.value.copy(error = "$rejected: ${resp.error}")
            } else {
                val label = if (msg.isNotEmpty()) msg else "(sent ${images.size} image(s))"
                _ui.value = _ui.value.copy(
                    items = _ui.value.items + ChatItem.UserText(
                        label,
                        key = "user-${System.nanoTime()}",
                        imageCount = images.size,
                    ),
                )
            }
        }
    }

    /**
     * TUI Esc: clear_queue first to recover unsent text, then abort.
     * abort alone does not clear the queue; if items remain they still run after the abort.
     */
    fun abort() {
        viewModelScope.launch {
            val rpc = client ?: return@launch
            val draft = clearQueueDraft(rpc)
            if (!draft.isNullOrBlank()) {
                _ui.value = _ui.value.copy(restoreDraft = mergeRestoreDraft(_ui.value.restoreDraft, draft))
            }
            val abortId = PiCommands.nextId()
            val resp = rpc.request(PiCommands.named("abort", id = abortId), abortId)
            if (resp != null && !resp.success) {
                _ui.value = _ui.value.copy(error = "Abort failed: ${resp.error}")
            }
        }
    }

    /** Clear the queue only (no abort) and restore the text into the composer. */
    fun clearQueueToEditor() {
        viewModelScope.launch {
            val rpc = client ?: return@launch
            val draft = clearQueueDraft(rpc)
            if (draft.isNullOrBlank()) {
                _ui.value = _ui.value.copy(error = "Queue is empty")
            } else {
                _ui.value = _ui.value.copy(restoreDraft = mergeRestoreDraft(_ui.value.restoreDraft, draft))
            }
        }
    }

    fun consumeRestoreDraft() {
        if (_ui.value.restoreDraft != null) {
            _ui.value = _ui.value.copy(restoreDraft = null)
        }
    }

    private suspend fun clearQueueDraft(rpc: PiRpcClient): String? {
        val id = PiCommands.nextId()
        val resp = rpc.request(PiCommands.named("clear_queue", id = id), id)
        if (resp == null) {
            AppLog.w(TAG, "clear_queue TIMEOUT")
            return null
        }
        if (!resp.success) {
            AppLog.w(TAG, "clear_queue failed: ${resp.error}")
            _ui.value = _ui.value.copy(error = "Clear queue failed: ${resp.error}")
            return null
        }
        val data = resp.data
        val steering = data?.stringList("steering").orEmpty()
        val followUp = data?.stringList("followUp").orEmpty()
        _ui.value = _ui.value.copy(queueSteering = emptyList(), queueFollowUp = emptyList())
        val draft = joinQueueDraft(steering, followUp)
        AppLog.i(TAG, "clear_queue ok steering=${steering.size} followUp=${followUp.size}")
        return draft.ifBlank { null }
    }

    private fun mergeRestoreDraft(existing: String?, incoming: String): String {
        val left = existing?.trim().orEmpty()
        val right = incoming.trim()
        return when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            else -> "$left\n\n$right"
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val id = PiCommands.nextId()
            AppLog.i(TAG, "new_session id=$id")
            val resp = client?.request(PiCommands.named("new_session", id = id), id)
            if (resp?.success == true) {
                AppLog.i(TAG, "new_session ok, cancelled=${resp.data?.get("cancelled")}")
                // After a new session, clear the resume target: a later reconnect should stay on the new session, not jump back
                lastSessionFile = null
                lastEntryId = null
                _ui.value = _ui.value.copy(
                    items = listOf(ChatItem.SystemNote("New session started", key = "new-session-${System.currentTimeMillis()}")),
                    statsText = null,
                    queueSteering = emptyList(),
                    queueFollowUp = emptyList(),
                    restoreDraft = null,
                )
                refreshAll()
            } else {
                val msg = "New session failed: ${resp?.error ?: "no response (timeout or disconnect) id=$id"}"
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
     * Probe when returning to foreground / network restores: the socket may have died while frozen,
     * but readLoop only reports Closed after thaw. Lightweight get_state; only treat as dead on timeout
     * with no inbound traffic, then tear down and reconnect so the UI does not stay on "Connected".
     * Skip if already reconnecting.
     */
    fun onForegroundResume() = probeAndMaybeReconnect("foreground resume")

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
            // State may have changed while waiting: manual disconnect / new connect / already reconnecting
            if (userDisconnect || client !== rpc || !_ui.value.connected) {
                AppLog.i(TAG, "probe TIMEOUT ignored (state changed)")
                return@launch
            }
            // Any inbound traffic during the timeout means alive: a long tool call can stall the command response,
            // and a false reconnect would kill the running agent
            if (rpc.lastInboundAtMs > sentAt) {
                AppLog.w(TAG, "probe TIMEOUT but inbound traffic seen ($reason); keep connection")
                return@launch
            }
            AppLog.w(TAG, "probe TIMEOUT ($reason); forcing reconnect")
            forceReconnect(reason)
        }
    }

    /** Tear down the current connection and enter the reconnect loop (probe timeout / network change). */
    private fun forceReconnect(reason: String) {
        viewModelScope.launch {
            closeResources()
            if (userDisconnect) return@launch
            scheduleReconnect(reason)
        }
    }

    /** Retry immediately (no backoff): for a just-restored network; backoff is for "remote still not ready". */
    private fun reconnectNow(reason: String) {
        if (userDisconnect) return
        AppLog.i(TAG, "reconnect now ($reason)")
        scheduleReconnect(reason, immediate = true)
    }

    /** Driven by PiScreen lifecycle: tighten heartbeat in background (spot OS reclaiming the socket), relax in foreground to save power. */
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
     * App-level heartbeat. Why SSH keepalive alone is not enough: transport keepalive only proves
     * the SSH link can exchange packets, and with SO_TIMEOUT=0 a dead socket blocks forever on read
     * without erroring — only an active probe plus inbound traffic can tell "link is dead" from
     * "agent is in a long tool call and not answering".
     */
    private fun restartHeartbeat() {
        val rpc = client ?: return
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (isActive && client === rpc && !userDisconnect) {
                val interval = if (backgrounded) HEARTBEAT_BG_MS else HEARTBEAT_FG_MS
                delay(interval)
                if (client !== rpc) return@launch
                // Recent inbound → link is alive, skip the probe
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
                forceReconnect("heartbeat timeout")
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
        // Routine refresh logs at D (not to disk); TIMEOUT / success=false are evidence
        if (resp?.success == true) AppLog.d(TAG, "get_state -> success=true err=null")
        else AppLog.w(TAG, "get_state -> ${if (resp == null) "TIMEOUT" else "success=${resp.success} err=${resp.error}"}")
        if (resp?.success == true && resp.data != null) {
            val state = PiState.from(JsonObject(mapOf("data" to resp.data!!)))
            // Remember the current session file so a reconnect can resume with --session
            state.sessionFile?.takeIf { it.isNotBlank() }?.let { lastSessionFile = it }
            locallyStreaming = state.isStreaming
            _ui.value = _ui.value.copy(state = state)
            // Also refresh thinking levels
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
                append(" · context ")
                append(formatPercent((ctx["percent"] as? JsonPrimitive)?.contentOrNull))
                append("%")
            }
        }
        _ui.value = _ui.value.copy(statsText = text)
    }

    /** Context percent keeps at most two decimals; integers drop trailing zeros (30 / 12.5 / 12.34). */
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
        // If the server cannot find the cursor, fall back to a full sync and reset local history; since must be cleared too,
        // otherwise the full snapshot merges onto the old list and the whole conversation appears twice
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
                        items.add(ChatItem.UserText(text.ifBlank { "(${imageCount} image(s))" }, key = "hist-u-${entry.str("id") ?: items.size}", timeMs = msg.timestamp ?: 0, imageCount = imageCount))
                    }
                }
                "assistant" -> {
                    val text = msg.blocks.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
                    val thinking = msg.blocks.filter { it.type == "thinking" }.joinToString("") { it.text ?: "" }
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        items.add(ChatItem.AssistantText(text, thinking.ifEmpty { null }, false, key = "hist-a-${entry.str("id") ?: items.size}", timeMs = msg.timestamp ?: 0))
                    }
                    for (tc in msg.blocks.filter { it.type == "toolCall" }) {
                        items.add(
                            ChatItem.ToolCard(
                                tc.toolCallId ?: "call-${items.size}",
                                tc.toolName ?: "?",
                                ChatCollapse.summarizeArgs(tc.argumentsJson),
                                null,
                                running = false,
                                isError = false,
                            ),
                        )
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
        val nextItems = if (since == null) base + items else mergeItems(base, items)
        _ui.value = _ui.value.copy(
            items = nextItems,
            historyEpoch = if (since == null) _ui.value.historyEpoch + 1 else _ui.value.historyEpoch,
        )
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
                // Directory of the active session: covers custom --session-dir cases as a fallback
                val fallbackDir = _ui.value.state?.sessionFile
                    ?.takeIf { it.isNotBlank() }
                    ?.substringBeforeLast('/', "")
                    ?.takeIf { it.isNotBlank() }
                // Portable session listing: do not use GNU find -printf (remote may be BusyBox/BSD find);
                // dirs from PI_CODING_AGENT_DIR/PI_CODING_AGENT_SESSION_DIR (same as upstream getAgentDir/getSessionsDir);
                // mtime via dual stat forms (GNU -c %Y / BSD -f %m), sort and take the latest 30;
                // display name is the last session_info name in each file (same naming the pi TUI stores)
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
                _ui.value = _ui.value.copy(error = "List sessions failed: ${e.message}")
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
     * Delete a session file. RPC has no delete command; same as pi TUI deleteSessionFile, delete at the file level.
     * Refuse to delete the active session (same as TUI). Quote the path with single quotes to avoid shell metachar injection.
     */
    fun deleteSession(path: String) {
        viewModelScope.launch {
            val s = activeSettings.value
            val active = _ui.value.state?.sessionFile
            if (active != null && pathsEqual(active, path)) {
                _ui.value = _ui.value.copy(error = "Cannot delete the active session; switch first")
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
                    _ui.value = _ui.value.copy(error = "Delete failed: $out")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "deleteSession ERROR: ${e.message}")
                _ui.value = _ui.value.copy(error = "Delete failed: ${e.message}")
            }
        }
    }

    private fun pathsEqual(a: String, b: String): Boolean {
        if (a == b) return true
        // Tolerate trailing differences: sessionFile may or may not include extension/relative prefix; compare normalized absolute paths
        val norm = { p: String -> p.removePrefix("~/").removePrefix("/root/").trimEnd('/') }
        return norm(a) == norm(b)
    }

    fun setSessionName(name: String) {
        viewModelScope.launch {
            val id = PiCommands.nextId()
            // The same id must go in the request body; otherwise the waiter waits on A while the server replies B → UNMATCHED + false TIMEOUT
            val resp = client?.request(PiCommands.setSessionNameReq(name, id = id), id)
            if (resp?.success == true) {
                _ui.value = _ui.value.copy(state = _ui.value.state?.copy(sessionName = name.ifBlank { null }))
            } else {
                _ui.value = _ui.value.copy(error = "Rename failed: ${resp?.error ?: "no response"}")
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

    /** Full log dump (for sharing); increments logSeq afterward so the log dialog refreshes. */
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
        // Close sockets when the Activity is truly destroyed so SSH/RPC do not leak until process exit.
        // Close blocks until the socket finishes; do it async on a dedicated IO scope, never runBlocking on the main thread (ANR risk)
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
        // Connection closed with the ViewModel, so keep-alive is pointless: stop immediately,
        // otherwise the FGS holds WakeLock/WifiLock idle until the 10-minute window ends (and with stopWithTask=false it can outlive swiping away the app)
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

/** Background heartbeat interval: tighten after backgrounding to spot the OS reclaiming the socket. */
private const val HEARTBEAT_BG_MS = 20_000L
/** Foreground heartbeat interval: RPC traffic already keeps the link alive; this is a fallback, prefer saving power. */
private const val HEARTBEAT_FG_MS = 60_000L
/** get_state timeout for heartbeat/probe; after timeout, only treat as dead if there was no inbound traffic. */
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

/** Pull id back out of a serialized command JSON so PiCommands does not expose internal state. */
private fun extractId(line: String): String? = try {
    (piJson.parseToJsonElement(line).jsonObject["id"] as? JsonPrimitive)?.contentOrNull
} catch (_: Exception) {
    null
}

private val TAG = "PiViewModel"
