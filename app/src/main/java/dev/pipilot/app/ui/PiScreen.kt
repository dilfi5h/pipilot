package dev.pipilot.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pipilot.app.chat.ChatCollapse
import dev.pipilot.app.chat.ChatItem
import dev.pipilot.app.chat.MarkdownText
import dev.pipilot.app.chat.ToolFamily
import dev.pipilot.app.chat.copyText
import dev.pipilot.app.ui.theme.ChatPalette
import dev.pipilot.app.keepalive.ConnectionKeepAliveService
import dev.pipilot.app.log.AppLog
import dev.pipilot.app.rpc.PiModel
import dev.pipilot.app.settings.ConnectionSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PiScreen(viewModel: PiViewModel) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.activeSettings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // pausedOrStopped: treat ON_PAUSE as leaving foreground so keep-alive starts earlier than ON_STOP
    var pausedOrStopped by remember { mutableStateOf(false) }
    var notifDeniedHinted by rememberSaveable { mutableStateOf(false) }
    var showBatteryHint by rememberSaveable { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.i("KeepAlive", "POST_NOTIFICATIONS granted=$granted")
        if (!granted && !notifDeniedHinted) {
            notifDeniedHinted = true
            // If denied, do not start FGS; still rely on heartbeat + foreground reconnect
            scope.launch {
                snackbar.showSnackbar("Notification permission off: background may drop soon; returning to foreground reconnects")
            }
        }
    }

    val needsKeepAlive = ui.connected || ui.reconnecting || ui.connecting
    // Observer closure reads the latest value so ON_PAUSE does not see a stale connected flag
    val needsKeepAliveRef = remember { mutableStateOf(needsKeepAlive) }
    needsKeepAliveRef.value = needsKeepAlive

    // After connect, request notification permission only when needed; battery-opt ignore is prompted once via an in-app dialog (not the system page directly)
    LaunchedEffect(ui.connected) {
        if (!ui.connected) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && !ConnectionKeepAliveService.canPostNotifications(context)) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // vivo/Xiaomi/OPPO freeze background networking; ignoring battery optimization is basically required for keep-alive; prompt once, user may refuse
        if (!isIgnoringBatteryOptimizations(context) && !dev.pipilot.app.PipilotApp.batteryPromptShown(context.filesDir)) {
            showBatteryHint = true
        }
    }

    if (showBatteryHint) {
        AlertDialog(
            onDismissRequest = { showBatteryHint = false },
            title = { Text("Background keep-alive needs one permission") },
            text = {
                Text(
                    "This device freezes app networking in the background; FGS + heartbeat alone cannot keep SSH up.\n\n" +
                        "Allow PiPilot to ignore battery optimization. For a stronger setup, also enable " +
                        "autostart / associated start and battery background running in system settings.\n\n" +
                        "You can refuse; the app still works, but background drops are more likely and returning to foreground reconnects.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dev.pipilot.app.PipilotApp.markBatteryPromptShown(context.filesDir)
                    showBatteryHint = false
                    maybeRequestIgnoreBatteryOptimizations(context)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dev.pipilot.app.PipilotApp.markBatteryPromptShown(context.filesDir)
                    showBatteryHint = false
                }) { Text("Not now") }
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    pausedOrStopped = true
                    viewModel.setBackgrounded(true)
                    AppLog.i("KeepAlive", "app pause needsKeepAlive=${needsKeepAliveRef.value}")
                    // Start FGS eagerly before the OS freezes the network; skip if not connected
                    if (needsKeepAliveRef.value && ConnectionKeepAliveService.canPostNotifications(context)) {
                        ConnectionKeepAliveService.start(context)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    pausedOrStopped = true
                    AppLog.i("KeepAlive", "app stop")
                }
                Lifecycle.Event.ON_START -> {
                    pausedOrStopped = false
                    viewModel.setBackgrounded(false)
                    AppLog.i("KeepAlive", "app start/foreground")
                    viewModel.onForegroundResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Need keep-alive and left foreground → start; back to foreground or fully disconnected → stop
    // Keep FGS during reconnect too, so "drop → stop service → harder to reconnect" does not thrash
    LaunchedEffect(needsKeepAlive, pausedOrStopped) {
        if (needsKeepAlive && pausedOrStopped) {
            if (ConnectionKeepAliveService.canPostNotifications(context)) {
                ConnectionKeepAliveService.start(context)
            } else {
                AppLog.i("KeepAlive", "skip FGS: notification permission missing")
            }
        } else if (!needsKeepAlive || !pausedOrStopped) {
            ConnectionKeepAliveService.stop(context)
        }
    }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "PiPilot v${dev.pipilot.app.BuildConfig.VERSION_NAME}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        if (ui.connected || ui.reconnecting) {
                            ModelPicker(viewModel, ui)
                            ThinkingPicker(viewModel, ui)
                            IconButton(onClick = { viewModel.listSessions(); showSessions = true }) {
                                Icon(Icons.Filled.List, "Sessions")
                            }
                            IconButton(onClick = { viewModel.newSession() }) {
                                Icon(Icons.Filled.Add, "New session")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, "Settings")
                        }
                    },
                )
                // Session name on its own row: full width, no longer fighting top-right icons; tap to rename
                if (ui.connected || ui.reconnecting) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(
                            text = sessionLabel(ui),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { showRename = true })
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        // Composer fixed in the bottom bar so it does not fight the chat list for space
        bottomBar = { if (ui.connected) InputBar(viewModel, ui) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Keep chat + banner while reconnecting; only never-connected (or user cancel) returns to the empty connect page
            if (!ui.connected && !ui.reconnecting) {
                NotConnectedView(ui, settings, viewModel)
            } else {
                if (ui.reconnecting) {
                    ReconnectBanner(ui, viewModel)
                }
                ChatList(ui, onClearQueue = viewModel::clearQueueToEditor, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings, viewModel, onDismiss = { showSettings = false })
    }
    if (showSessions) {
        SessionsSheet(ui, viewModel, onDismiss = { showSessions = false })
    }
    if (showRename) {
        RenameDialog(currentName = ui.state?.sessionName.orEmpty(), viewModel, onDismiss = { showRename = false })
    }
    ui.dialog?.let { d ->
        ExtensionDialog(d, onAnswer = viewModel::answerDialog)
    }
}

private fun sessionLabel(ui: UiState): String {
    // Session name → file name → "Disconnected"; shared by the title strip
    return ui.state?.sessionName?.takeIf { it.isNotBlank() }
        ?: ui.state?.sessionFile?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Disconnected"
}

@Composable
private fun ModelPicker(viewModel: PiViewModel, ui: UiState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Memory, "Model") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ui.models.isEmpty()) {
                DropdownMenuItem(text = { Text("No models available") }, onClick = { open = false })
            }
            ui.models.forEach { m ->
                val current = ui.state?.model?.id == m.id && ui.state?.model?.provider == m.provider
                DropdownMenuItem(
                    text = { Text((if (current) "✓ " else "") + "${m.provider}/${m.id}") },
                    onClick = { viewModel.setModel(m); open = false },
                )
            }
        }
    }
}

@Composable
private fun ThinkingPicker(viewModel: PiViewModel, ui: UiState) {
    var open by remember { mutableStateOf(false) }
    if (ui.thinkingLevels.size <= 1) return
    Box {
        TextButton(onClick = { open = true }) {
            Text(ui.state?.thinkingLevel ?: "Thinking", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.thinkingLevels.forEach { lv ->
                DropdownMenuItem(
                    text = { Text(if (lv == ui.state?.thinkingLevel) "✓ $lv" else lv) },
                    onClick = { viewModel.setThinkingLevel(lv); open = false },
                )
            }
        }
    }
}

@Composable
private fun NotConnectedView(ui: UiState, settings: ConnectionSettings, viewModel: PiViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Connect to a machine running pi", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            if (settings.host.isBlank()) "Fill SSH host, user, and password/key in ⚙ Settings (top right)."
            else "Target: ${settings.user}@${settings.host}:${settings.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        if (ui.connecting) {
            CircularProgressIndicator()
            Spacer(Modifier.size(8.dp))
            Text(ui.connectionLabel)
        } else if (ui.reconnecting) {
            CircularProgressIndicator()
            Spacer(Modifier.size(8.dp))
            Text(ui.connectionLabel)
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { viewModel.cancelReconnect() }) { Text("Cancel reconnect") }
        } else {
            Button(onClick = { viewModel.connect() }) { Text("Connect") }
        }
        ui.error?.let {
            Spacer(Modifier.size(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReconnectBanner(ui: UiState, viewModel: PiViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                ui.connectionLabel,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.cancelReconnect() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun QueueBanner(ui: UiState, onClear: () -> Unit) {
    val parts = buildList {
        if (ui.queueSteering.isNotEmpty()) add("steer ${ui.queueSteering.size}")
        if (ui.queueFollowUp.isNotEmpty()) add("queue ${ui.queueFollowUp.size}")
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Pending: ${parts.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Recall") }
        }
    }
}

@Composable
private fun ChatList(ui: UiState, onClearQueue: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val lastItemLen = ui.items.lastOrNull()?.let { it.textLength() } ?: 0
    val lastKey = ui.items.lastOrNull()?.key
    val hasQueue = ui.queueSteering.isNotEmpty() || ui.queueFollowUp.isNotEmpty()
    val lastListIndex = ui.items.size + (if (hasQueue) 1 else 0) - 1
    val followBottom = remember { mutableStateOf(true) }
    val pinningBottom = remember { mutableStateOf(false) }
    val jumpScope = rememberCoroutineScope()
    suspend fun pinToBottom(animate: Boolean = false) {
        pinningBottom.value = true
        try {
            if (listState.layoutInfo.totalItemsCount <= 0) {
                withTimeoutOrNull(1_000) {
                    snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                }
            }
            listState.scrollToLastItemBottom(animate)
        } finally {
            pinningBottom.value = false
        }
    }
    // Full history rebuild (reconnect / session switch) lands at index 0.
    // Re-enable follow and pin to the latest output; a short background
    // resume that did not rebuild history leaves the user's place alone.
    LaunchedEffect(ui.historyEpoch) {
        if (ui.historyEpoch == 0L || lastListIndex < 0) return@LaunchedEffect
        followBottom.value = true
        pinToBottom()
    }
    // Pin the end of a growing last item, not its start. Ignore content-driven
    // jumps while the user is dragging so a swipe is not cancelled mid-gesture.
    LaunchedEffect(ui.items.size, lastKey, lastItemLen, hasQueue, followBottom.value) {
        if (!followBottom.value || lastListIndex < 0) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            Triple(last?.index, last?.size, listState.layoutInfo.viewportEndOffset)
        }.collect {
            if (!followBottom.value || listState.isScrollInProgress || pinningBottom.value) return@collect
            if (!listState.isAtChatBottom(lastListIndex)) {
                pinToBottom()
            }
        }
    }
    LaunchedEffect(listState, lastListIndex) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            Triple(listState.isScrollInProgress, last?.offset, last?.size)
        }.collect { (inProgress, _, _) ->
            if (pinningBottom.value || listState.layoutInfo.visibleItemsInfo.isEmpty()) return@collect
            val atBottom = listState.isAtChatBottom(lastListIndex)
            followBottom.value = ChatScroll.followAfterIdleLayout(
                currentlyFollowing = followBottom.value,
                userDragging = inProgress,
                atBottom = atBottom,
            )
        }
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.UserText -> UserBubble(item)
                    is ChatItem.AssistantText -> AssistantBubble(item)
                    is ChatItem.ToolCard -> ToolCard(item)
                    is ChatItem.BashOutput -> BashCard(item)
                    is ChatItem.SystemNote -> Text(
                        item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(0.8f),
                    )
                    // RemoveLive is consumed only in the VM and never enters the list; ignore as a fallback
                    else -> Unit
                }
            }
            if (hasQueue) {
                item(key = "queue") {
                    QueueBanner(ui, onClear = onClearQueue)
                }
            }
        }
        // Shown after scrolling up; tap to jump to the latest output
        if (!followBottom.value) {
            IconButton(
                onClick = {
                    followBottom.value = true
                    jumpScope.launch { pinToBottom(animate = true) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, "Scroll to bottom")
            }
        }
    }
}

private fun LazyListState.isAtChatBottom(lastIndex: Int): Boolean {
    val info = layoutInfo
    return ChatScroll.isAtBottom(
        itemCount = info.totalItemsCount,
        viewportEndOffset = info.viewportEndOffset,
        visible = info.visibleItemsInfo.map {
            ChatVisibleItem(index = it.index, offset = it.offset, size = it.size)
        },
        lastIndex = lastIndex,
        afterContentPadding = info.afterContentPadding,
    )
}

private suspend fun LazyListState.scrollToLastItemBottom(animate: Boolean = false) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    suspend fun jump(offset: Int) {
        if (animate) animateScrollToItem(lastIndex, offset) else scrollToItem(lastIndex, offset)
    }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    val offset = if (last != null) {
        ChatScroll.lastItemBottomOffset(
            itemSize = last.size,
            viewportSize = info.viewportEndOffset - info.viewportStartOffset,
            afterContentPadding = info.afterContentPadding,
        )
    } else {
        Int.MAX_VALUE / 4
    }
    jump(offset)
    val settled = layoutInfo
    val settledLast = settled.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val settledOffset = ChatScroll.lastItemBottomOffset(
        itemSize = settledLast.size,
        viewportSize = settled.viewportEndOffset - settled.viewportStartOffset,
        afterContentPadding = settled.afterContentPadding,
    )
    if (settledOffset != offset) jump(settledOffset)
}

/** Estimate item content length to detect "last item is still growing" while streaming. */
private fun ChatItem.textLength(): Int = when (this) {
    is ChatItem.UserText -> text.length
    is ChatItem.AssistantText -> text.length
    is ChatItem.ToolCard -> (output?.length ?: 0)
    is ChatItem.BashOutput -> output.length
    is ChatItem.SystemNote -> text.length
    else -> 0
}

@Composable
private fun CopyItemButton(text: String, contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val clipboard = LocalClipboardManager.current
    DisableSelection {
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "Copy message",
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(item: ChatItem.UserText) {
    val copy = item.copyText()
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            ) {
                SelectionContainer {
                    Column(Modifier.padding(12.dp)) {
                        if (item.imageCount > 0) {
                            Text(
                                "🖼 × ${item.imageCount}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (item.text.isNotBlank()) Spacer(Modifier.size(4.dp))
                        }
                        if (item.text.isNotBlank()) {
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(end = 4.dp, top = 2.dp),
        ) {
            if (item.timeMs > 0) {
                Text(
                    dev.pipilot.app.chat.formatShanghai(item.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (copy != null) CopyItemButton(copy)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantBubble(item: ChatItem.AssistantText) {
    val copy = item.copyText()
    val hasText = item.text.isNotBlank() || item.streaming
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!item.thinking.isNullOrBlank()) {
            ThinkingCard(thinking = item.thinking, streaming = item.streaming)
        }
        if (hasText) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    ) {
                        val body = @Composable {
                            Column(Modifier.padding(12.dp).animateContentSize()) {
                                MarkdownText(item.text)
                                if (item.streaming) BlinkingCursor()
                            }
                        }
                        if (item.streaming) body() else SelectionContainer { body() }
                    }
                }
                if (!item.streaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    ) {
                        if (item.timeMs > 0) {
                            Text(
                                dev.pipilot.app.chat.formatShanghai(item.timeMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (copy != null) CopyItemButton(copy)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingCard(thinking: String, streaming: Boolean) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = ChatCollapse.isExpanded(active = streaming, userExpanded = userExpanded)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                drawRoundRect(
                    color = outline,
                    style = stroke,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(10.dp)
            .animateContentSize(),
    ) {
        CollapseHeader(
            title = if (streaming) "Thinking…" else "Thinking",
            expanded = expanded,
            onToggle = { userExpanded = !expanded },
        )
        if (expanded) {
            Text(
                ChatCollapse.expandedBody(thinking, fromEnd = false),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val preview = ChatCollapse.thinkingPreview(thinking)
            if (preview.isNotEmpty()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CollapseHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall,
    titleFontFamily: FontFamily? = null,
    chevronTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    DisableSelection {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Text(
                    title,
                    style = titleStyle,
                    fontFamily = titleFontFamily,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = chevronTint,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val alpha = rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text("▍", color = MaterialTheme.colorScheme.primary, modifier = Modifier.alpha(alpha.value))
}

@Composable
private fun ToolCard(item: ChatItem.ToolCard) {
    val family = ChatCollapse.toolFamily(item.toolName)
    if (family == ToolFamily.Bash) {
        BashLikeCard(
            key = item.key,
            command = item.argsSummary,
            output = item.output.orEmpty(),
            running = item.running,
            isError = item.isError,
            copy = item.copyText(),
        )
        return
    }
    val copy = item.copyText()
    var userExpanded by remember(item.key) { mutableStateOf<Boolean?>(null) }
    val expanded = ChatCollapse.isExpanded(active = item.running, userExpanded = userExpanded)
    val dark = isSystemInDarkTheme()
    val chrome = ChatPalette.tool(family, dark)
    val path = item.argsSummary?.takeIf { it.isNotBlank() }
    val output = item.output?.takeIf { it.isNotEmpty() }
    val renderMd = family == ToolFamily.Read && path != null && ChatCollapse.isMarkdownPath(path) && output != null
    Surface(color = chrome.background, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(chrome.accent, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
            Column(Modifier.padding(10.dp).weight(1f).animateContentSize()) {
                CollapseHeader(
                    title = item.toolName,
                    expanded = expanded,
                    onToggle = { userExpanded = !expanded },
                    titleStyle = MaterialTheme.typography.labelLarge,
                    titleFontFamily = FontFamily.Monospace,
                    titleColor = chrome.accent,
                    chevronTint = chrome.accent,
                    trailing = {
                        if (item.running) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = chrome.accent)
                            Spacer(Modifier.size(6.dp))
                        } else {
                            Icon(
                                if (item.isError) Icons.Filled.Close else Icons.Filled.Check,
                                null, Modifier.size(14.dp),
                                tint = if (item.isError) MaterialTheme.colorScheme.error else ChatPalette.ok,
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        if (copy != null) CopyItemButton(copy, contentColor = chrome.accent)
                    },
                )
                if (path != null) {
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = chrome.accent,
                    )
                }
                if (expanded) {
                    SelectionContainer {
                        if (renderMd) {
                            MarkdownText(ChatCollapse.expandedBody(output!!, fromEnd = false))
                        } else if (output != null) {
                            Text(
                                ChatCollapse.expandedBody(output, fromEnd = true),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val preview = when {
                        output != null -> ChatCollapse.outputPreview(output)
                        path != null -> ChatCollapse.thinkingPreview(path)
                        else -> ""
                    }
                    if (preview.isNotEmpty()) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BashCard(item: ChatItem.BashOutput) {
    BashLikeCard(
        key = item.key,
        command = item.command,
        output = item.output,
        running = item.running,
        isError = false,
        copy = item.copyText(),
    )
}

@Composable
private fun BashLikeCard(
    key: String,
    command: String?,
    output: String,
    running: Boolean,
    isError: Boolean,
    copy: String?,
) {
    var userExpanded by remember(key) { mutableStateOf<Boolean?>(null) }
    val expanded = ChatCollapse.isExpanded(active = running, userExpanded = userExpanded)
    Surface(color = ChatPalette.bashBg, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp).fillMaxWidth().animateContentSize()) {
            CollapseHeader(
                title = "bash",
                expanded = expanded,
                onToggle = { userExpanded = !expanded },
                titleColor = ChatPalette.bashCmd,
                titleStyle = MaterialTheme.typography.labelLarge,
                titleFontFamily = FontFamily.Monospace,
                chevronTint = ChatPalette.bashMuted,
                trailing = {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ChatPalette.bashCmd)
                        Spacer(Modifier.size(6.dp))
                    } else {
                        Icon(
                            if (isError) Icons.Filled.Close else Icons.Filled.Check,
                            null, Modifier.size(14.dp),
                            tint = if (isError) MaterialTheme.colorScheme.error else ChatPalette.ok,
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                    if (copy != null) CopyItemButton(copy, contentColor = ChatPalette.bashMuted)
                },
            )
            val cmd = command?.takeIf { it.isNotBlank() }
            if (cmd != null) {
                Text(
                    if (cmd.startsWith("$ ")) cmd else "$ $cmd",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ChatPalette.bashCmd,
                )
            }
            if (expanded) {
                SelectionContainer {
                    Column {
                        if (output.isNotEmpty()) {
                            Text(
                                ChatCollapse.expandedBody(output, fromEnd = true),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = ChatPalette.bashFg,
                            )
                        }
                        if (running) Text("…", color = ChatPalette.bashFg, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                val preview = ChatCollapse.outputPreview(output)
                if (preview.isNotEmpty()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ChatPalette.bashFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (running) {
                    Text("…", color = ChatPalette.bashFg, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun InputBar(viewModel: PiViewModel, ui: UiState) {
    var text by rememberSaveable { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    // Pending image base64 is large; keep it in remember, not rememberSaveable
    val pendingImagePayloads = remember { mutableStateListOf<dev.pipilot.app.chat.PreparedImage>() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val slotsLeft = (4 - pendingImagePayloads.size).coerceAtLeast(0)
        if (slotsLeft <= 0) {
            android.widget.Toast.makeText(context, "At most 4 images", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        preparing = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prepared = uris.take(slotsLeft).mapNotNull { uri ->
                runCatching { dev.pipilot.app.chat.prepareImageForUpload(context, uri) }
                    .onFailure { dev.pipilot.app.log.AppLog.e("InputBar", "prepareImage failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (prepared.size < uris.size) {
                    android.widget.Toast.makeText(context, "${uris.size - prepared.size} image(s) failed to load and were skipped", android.widget.Toast.LENGTH_SHORT).show()
                }
                pendingImagePayloads.addAll(prepared)
                preparing = false
            }
            dev.pipilot.app.log.AppLog.i(
                "InputBar",
                "attach OK: ${prepared.size}/${uris.size} img, total ${prepared.sumOf { it.sizeKb }}KB",
            )
        }
    }
    LaunchedEffect(ui.restoreDraft) {
        val draft = ui.restoreDraft ?: return@LaunchedEffect
        text = if (text.isBlank()) draft else "${text.trim()}\n\n$draft"
        viewModel.consumeRestoreDraft()
    }
    fun takeImages(): List<Pair<String, String>> {
        val images = pendingImagePayloads.map { it.base64 to it.mimeType }
        if (images.isNotEmpty()) {
            val kb = images.sumOf { it.first.length } / 1024
            dev.pipilot.app.log.AppLog.i("InputBar", "send with ${images.size} image(s), ~${kb}KB base64")
        }
        return images
    }
    fun afterSend() {
        text = ""
        pendingImagePayloads.clear()
    }
    val canSend = text.isNotBlank() || pendingImagePayloads.isNotEmpty()
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            // Status strip: model · thinking · tokens/context; fixed single line above the composer, never covers it
            StatusStrip(ui)
            if (pendingImagePayloads.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pendingImagePayloads.size) { idx ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                androidx.compose.foundation.Image(
                                    bitmap = pendingImagePayloads[idx].thumbnail.asImageBitmap(),
                                    contentDescription = "Pending image ${idx + 1}",
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        ),
                                )
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) {
                                    IconButton(
                                        onClick = { pendingImagePayloads.removeAt(idx) },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Text(
                                "${pendingImagePayloads[idx].sizeKb}KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val streaming = ui.state?.isStreaming == true
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !preparing && pendingImagePayloads.size < 4,
                ) {
                    if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Image, "Add image")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                streaming -> "Steer or queue…"
                                else -> "Message pi"
                            },
                        )
                    },
                    maxLines = 5,
                )
                if (streaming) {
                    IconButton(onClick = { viewModel.abort() }) {
                        Icon(Icons.Filled.Stop, "Abort and recall queue", tint = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            viewModel.sendSteer(text, takeImages())
                            afterSend()
                        },
                        enabled = canSend,
                    ) { Text("Steer") }
                    TextButton(
                        onClick = {
                            viewModel.sendFollowUp(text, takeImages())
                            afterSend()
                        },
                        enabled = canSend,
                    ) { Text("Queue") }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.sendPrompt(text, takeImages())
                            afterSend()
                        },
                        enabled = canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(ui: UiState) {
    val model = ui.state?.model
    val left = buildString {
        append(model?.displayName ?: "No model selected")
        ui.state?.thinkingLevel?.let { append(" · $it") }
    }
    // Left: model/thinking may ellipsize; right: tokens/context stay visible so a long number does not push context off-screen
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = left,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ui.statsText?.let { stats ->
            Text(
                text = stats,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(settings: ConnectionSettings, viewModel: PiViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    var profileName by remember(profiles.activeProfileName, settings) {
        mutableStateOf(profiles.activeProfileName ?: "default")
    }
    var draft by remember(profiles.activeProfileName, settings) { mutableStateOf(settings) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text("SSH connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            if (profiles.profiles.size > 1 || (profiles.profiles.size == 1 && profiles.profiles[0].name != profileName)) {
                profiles.profiles.forEach { p ->
                    val selected = p.name == profiles.activeProfileName
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.setActiveProfile(p.name)
                                profileName = p.name
                                draft = p.settings
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                (if (selected) "✓ " else "") + p.name +
                                    " (${p.settings.user}@${p.settings.host})",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        if (!selected) {
                            TextButton(onClick = { viewModel.deleteProfile(p.name) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
            OutlinedTextField(
                value = profileName, onValueChange = { profileName = it },
                label = { Text("Profile name (saved as multi-host config)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.host, onValueChange = { draft = draft.copy(host = it) },
                    label = { Text("Host") }, modifier = Modifier.weight(2f), singleLine = true,
                )
                OutlinedTextField(
                    value = draft.port, onValueChange = { draft = draft.copy(port = it) },
                    label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            OutlinedTextField(
                value = draft.user, onValueChange = { draft = draft.copy(user = it) },
                label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipSimple("Password", draft.authType == "password") { draft = draft.copy(authType = "password") }
                FilterChipSimple("Private key", draft.authType == "key") { draft = draft.copy(authType = "key") }
            }
            if (draft.authType == "password") {
                OutlinedTextField(
                    value = draft.password, onValueChange = { draft = draft.copy(password = it) },
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            } else {
                OutlinedTextField(
                    value = draft.privateKey, onValueChange = { draft = draft.copy(privateKey = it) },
                    label = { Text("Private key (full PEM)") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = draft.keyPassphrase, onValueChange = { draft = draft.copy(keyPassphrase = it) },
                    label = { Text("Key passphrase (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            Spacer(Modifier.size(12.dp))
            Text("pi launch", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.piCommand, onValueChange = { draft = draft.copy(piCommand = it) },
                label = { Text("Launch command") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = draft.workDir, onValueChange = { draft = draft.copy(workDir = it) },
                label = { Text("Working directory (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.size(16.dp))
            Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.saveProfile(profileName, draft)
                    onDismiss()
                }) { Text("Save") }
                if (viewModel.ui.value.connected) {
                    OutlinedButton(onClick = {
                        viewModel.saveProfile(profileName, draft)
                        viewModel.disconnect()
                        onDismiss()
                    }) { Text("Save & disconnect") }
                }
            }
            Spacer(Modifier.size(12.dp))
            Text("Background keep-alive", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "Briefly keep the connection in the background (~10 minutes) with an app-level heartbeat every 20 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "vivo/Xiaomi/OPPO also freeze background networking; allow these in system settings or rely on foreground reconnect:\n" +
                    "· Allow Ignore battery optimization\n" +
                    "· Enable Autostart / Associated start in app details\n" +
                    "· Allow background running / high background power use (vivo OriginOS)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= 23) {
                val ok = isIgnoringBatteryOptimizations(ctx)
                Spacer(Modifier.size(4.dp))
                Text(
                    if (ok) "Status: battery optimization ignored ✅" else "Status: battery optimization still on ⚠️ background network may freeze",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { maybeRequestIgnoreBatteryOptimizations(ctx) }) {
                    Text("Ignore battery optimization")
                }
                OutlinedButton(onClick = { openAppDetailsSettings(ctx) }) {
                    Text("App details")
                }
            }
            Spacer(Modifier.size(8.dp))
            Text("Runtime logs (copy when filing a bug)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            LogPanel(viewModel)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun LogPanel(viewModel: PiViewModel) {
    val ui by viewModel.ui.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // Re-read whenever logSeq changes
    val text = remember(ui.logSeq) { viewModel.getLogText() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = LocalContext.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                viewModel.refreshLogView()
                expanded = !expanded
            }) { Text(if (expanded) "Hide logs" else "View logs") }
            OutlinedButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.ifBlank { "(empty)" }))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                // Share the on-disk log via the system sheet: the whole file is not truncated by paste/attachments
                val f = dev.pipilot.app.log.AppLog.logFile()
                if (f == null || !f.exists() || f.length() == 0L) {
                    android.widget.Toast.makeText(ctx, "No on-disk log yet", android.widget.Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", f,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "PiPilot runtime log")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Share log file"))
                    AppLog.i("KeepAlive", "shared log file ${f.length()}B")
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text("Share file") }
            TextButton(onClick = { viewModel.clearLog() }) { Text("Clear") }
        }
        if (expanded) {
            Spacer(Modifier.size(8.dp))
            SelectionContainer {
                Text(
                    text.ifBlank { "(no logs yet)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterChipSimple(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(if (selected) "● $label" else "○ $label")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SessionsSheet(ui: UiState, viewModel: PiViewModel, onDismiss: () -> Unit) {
    var pendingDelete by remember { mutableStateOf<SessionEntryInfo?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Sessions (session files)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Tap to switch · long-press to delete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            if (ui.sessionsLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Listing sessions…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (ui.sessions.isEmpty()) {
                Text(
                    "No sessions found under ${ui.sessionsDir ?: "~/.pi/agent/sessions"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                items(ui.sessions, key = { it.path }) { s ->
                    // Do not stack TextButton + combinedClickable: the button already has clickable,
                    // the inner dispatcher gets the event first, and a long-press release is eaten as a click so onLongClick never fires.
                    // A plain Text with one combinedClickable lets tap and long-press both work.
                    Text(
                        text = s.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    viewModel.switchSession(s.path)
                                    onDismiss()
                                },
                                onLongClick = { pendingDelete = s },
                            )
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete session?") },
            text = { Text("${target.name}\n\nThis permanently deletes the session file and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(target.path)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenameDialog(currentName: String, viewModel: PiViewModel, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Leave blank to clear the name and show the file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setSessionName(input.trim())
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExtensionDialog(d: UiDialog, onAnswer: (DialogAnswer) -> Unit) {
    var input by remember(d.id) { mutableStateOf(d.prefill ?: "") }
    AlertDialog(
        onDismissRequest = { onAnswer(DialogAnswer.Cancel) },
        title = { Text(d.title.ifBlank { "pi needs input" }) },
        text = {
            Column {
                d.message?.let { Text(it) }
                when (d.method) {
                    "select" -> {
                        d.options.forEach { opt ->
                            TextButton(onClick = { onAnswer(DialogAnswer.Value(opt)) }) { Text(opt) }
                        }
                    }
                    "input", "editor" -> {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(d.placeholder ?: "") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (d.method == "editor") 4 else 1,
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (d.method) {
                "confirm" -> Row {
                    TextButton(onClick = { onAnswer(DialogAnswer.Confirm(false)) }) { Text("No") }
                    TextButton(onClick = { onAnswer(DialogAnswer.Confirm(true)) }) { Text("Yes") }
                }
                "input", "editor" -> TextButton(onClick = { onAnswer(DialogAnswer.Value(input)) }) { Text("OK") }
                else -> TextButton(onClick = { onAnswer(DialogAnswer.Cancel) }) { Text("Close") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(DialogAnswer.Cancel) }) { Text("Cancel") }
        },
    )
}

/** Whether battery optimization is ignored (main switch for vivo/Xiaomi freezing background networking). */
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

/** After connect, request ignore-battery-optimization; without it OEMs often freeze background networking. Failures are logged only. */
private fun maybeRequestIgnoreBatteryOptimizations(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < 23) return
    val pm = context.getSystemService(PowerManager::class.java) ?: return
    val pkg = context.packageName
    if (pm.isIgnoringBatteryOptimizations(pkg)) {
        AppLog.i("KeepAlive", "already ignoring battery optimizations")
        return
    }
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        AppLog.i("KeepAlive", "requested IGNORE_BATTERY_OPTIMIZATIONS")
    } catch (e: Exception) {
        AppLog.e("KeepAlive", "battery opt request failed: ${e.javaClass.simpleName}: ${e.message}")
    }
}

/** Open the system App details page: vivo/Xiaomi autostart / associated start / background running switches live there. */
private fun openAppDetailsSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        AppLog.i("KeepAlive", "opened app details settings")
    } catch (e: Exception) {
        AppLog.e("KeepAlive", "open app details failed: ${e.javaClass.simpleName}: ${e.message}")
    }
}
