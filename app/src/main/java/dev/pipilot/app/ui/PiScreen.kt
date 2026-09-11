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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pipilot.app.chat.ChatItem
import dev.pipilot.app.chat.MarkdownText
import dev.pipilot.app.rpc.PiModel
import dev.pipilot.app.settings.ConnectionSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PiScreen(viewModel: PiViewModel) {
    val ui by viewModel.ui.collectAsState()
    val settings by viewModel.activeSettings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
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
                        if (ui.connected) {
                            ModelPicker(viewModel, ui)
                            ThinkingPicker(viewModel, ui)
                            IconButton(onClick = { viewModel.listSessions(); showSessions = true }) {
                                Icon(Icons.Filled.List, "会话")
                            }
                            IconButton(onClick = { viewModel.newSession() }) {
                                Icon(Icons.Filled.Add, "新会话")
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, "设置")
                        }
                    },
                )
                // 会话名独立成一行:独占整行宽度,不再跟右上角图标抢地方;点击可重命名
                if (ui.connected) {
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
        // 输入框固定底部槽位,不再跟聊天列表抢空间
        bottomBar = { if (ui.connected) InputBar(viewModel, ui) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!ui.connected) {
                NotConnectedView(ui, settings, viewModel)
            } else {
                if (ui.reconnecting) {
                    ReconnectBanner(ui, viewModel)
                }
                ChatList(ui, modifier = Modifier.weight(1f))
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
    // 会话名→文件名→"未连接",标题栏横条共用
    return ui.state?.sessionName?.takeIf { it.isNotBlank() }
        ?: ui.state?.sessionFile?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "未连接"
}

@Composable
private fun ModelPicker(viewModel: PiViewModel, ui: UiState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Memory, "模型") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ui.models.isEmpty()) {
                DropdownMenuItem(text = { Text("无可用模型") }, onClick = { open = false })
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
            Text(ui.state?.thinkingLevel ?: "思考", style = MaterialTheme.typography.labelLarge)
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
        Text("连接到运行 pi 的机器", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            if (settings.host.isBlank()) "先在右上角 ⚙ 设置里填 SSH 主机、用户和密码/密钥。"
            else "目标:${settings.user}@${settings.host}:${settings.port}",
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
            OutlinedButton(onClick = { viewModel.cancelReconnect() }) { Text("取消重连") }
        } else {
            Button(onClick = { viewModel.connect() }) { Text("连接") }
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
            TextButton(onClick = { viewModel.cancelReconnect() }) { Text("取消") }
        }
    }
}

@Composable
private fun ChatList(ui: UiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // 条数变化或最后一条内容变化都贴底;用户主动往上翻(不在底部)时不打扰
    val lastItemLen = ui.items.lastOrNull()?.let { it.textLength() } ?: 0
    val lastKey = ui.items.lastOrNull()?.key
    val followBottom = remember(ui.items.size) { mutableStateOf(true) }
    LaunchedEffect(ui.items.size) {
        if (ui.items.isNotEmpty() && followBottom.value) listState.animateScrollToItem(ui.items.size - 1)
    }
    LaunchedEffect(lastKey, lastItemLen) {
        if (ui.items.isNotEmpty() && followBottom.value) listState.scrollToItem(ui.items.size - 1)
    }
    // 用户往回滚(距底 >2 条)就停止跟随,回到底部附近恢复
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= ui.items.size - 2 } ?: true
            followBottom.value = atBottom
        }
    }
    Box(modifier) {
        SelectionContainer(Modifier.fillMaxSize()) {
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
                        // RemoveLive 只在 VM 层消费,永远不会进列表,兜底不渲染
                        else -> Unit
                    }
                }
                if (ui.queueSteering.isNotEmpty() || ui.queueFollowUp.isNotEmpty()) {
                    item(key = "queue") {
                        Text(
                            "排队中:${ui.queueSteering.size} 条 steering,${ui.queueFollowUp.size} 条 follow-up",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // 上翻后出现,点击回到最新消息
        if (!followBottom.value) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            IconButton(
                onClick = {
                    followBottom.value = true
                    if (ui.items.isNotEmpty()) scope.launch { listState.animateScrollToItem(ui.items.size - 1) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, "滚到底部")
            }
        }
    }
}

/** 估算条目内容长度,用于流式期间检测"最后一条在变长"。*/
private fun ChatItem.textLength(): Int = when (this) {
    is ChatItem.UserText -> text.length
    is ChatItem.AssistantText -> text.length
    is ChatItem.ToolCard -> (output?.length ?: 0)
    is ChatItem.BashOutput -> output.length
    is ChatItem.SystemNote -> text.length
    else -> 0
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(item: ChatItem.UserText) {
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            ) {
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
        if (item.timeMs > 0) {
            Text(
                dev.pipilot.app.chat.formatShanghai(item.timeMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantBubble(item: ChatItem.AssistantText) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            ) {
                Column(Modifier.padding(12.dp).widthIn(max = 320.dp).animateContentSize()) {
                    if (!item.thinking.isNullOrBlank()) {
                        Text(
                            "思考中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.thinking.take(600),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    MarkdownText(item.text)
                    if (item.streaming) {
                        BlinkingCursor()
                    }
                }
            }
        }
        // 流式中的 live 气泡不显示时间(时间会每帧变,等落 final 再显示)
        if (!item.streaming && item.timeMs > 0) {
            Text(
                dev.pipilot.app.chat.formatShanghai(item.timeMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
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
    Surface(
        color = if (item.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(10.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Build, null,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    item.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
                if (item.running) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                } else {
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        if (item.isError) Icons.Filled.Close else Icons.Filled.Check,
                        null, Modifier.size(14.dp),
                        tint = if (item.isError) MaterialTheme.colorScheme.error else Color(0xFF43A047),
                    )
                }
            }
            item.argsSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            item.output?.let { out ->
                Spacer(Modifier.size(6.dp))
                Text(
                    out.takeLast(2000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 10,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BashCard(item: ChatItem.BashOutput) {
    Surface(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp).fillMaxWidth()) {
            item.command?.let {
                Text("$ ${it.takeLast(120)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color(0xFF9CCC65))
            }
            Text(
                item.output.takeLast(3000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFD4D4D4),
                maxLines = 14,
            )
            if (item.running) Text("…", color = Color(0xFFD4D4D4), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InputBar(viewModel: PiViewModel, ui: UiState) {
    var text by rememberSaveable { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    // 待发送图片的 base64 体积大,不进 rememberSaveable,只放 composition 内的 remember
    val pendingImagePayloads = remember { mutableStateListOf<dev.pipilot.app.chat.PreparedImage>() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val slotsLeft = (4 - pendingImagePayloads.size).coerceAtLeast(0)
        if (slotsLeft <= 0) {
            android.widget.Toast.makeText(context, "最多附带 4 张图片", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        preparing = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prepared = uris.take(slotsLeft).mapNotNull { uri ->
                runCatching { dev.pipilot.app.chat.prepareImageForUpload(context, uri) }
                    .onFailure { dev.pipilot.app.log.AppLog.e("InputBar", "prepareImage failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            if (prepared.size < uris.size) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "有 ${uris.size - prepared.size} 张图片读取失败,已跳过", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            pendingImagePayloads.addAll(prepared)
            preparing = false
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            // 状态条:模型 · thinking · token/上下文,固定单行,输入框正上方,绝不遮挡
            StatusStrip(ui)
            if (pendingImagePayloads.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pendingImagePayloads.size) { idx ->
                        Box {
                            androidx.compose.foundation.Image(
                                bitmap = pendingImagePayloads[idx].thumbnail.asImageBitmap(),
                                contentDescription = "待发送图片 ${idx + 1}",
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
                                    Icon(Icons.Filled.Close, "移除", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
            val streaming = ui.state?.isStreaming == true
            IconButton(
                onClick = { picker.launch("image/*") },
                enabled = !preparing && pendingImagePayloads.size < 4,
            ) {
                if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Image, "添加图片")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (streaming) "排队消息(follow-up)…" else "给 pi 发消息") },
                maxLines = 5,
            )
            if (streaming) {
                IconButton(onClick = { viewModel.abort() }) {
                    Icon(Icons.Filled.Stop, "中止", tint = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(
                onClick = {
                    val images = pendingImagePayloads.map { it.base64 to it.mimeType }
                    if (images.isNotEmpty()) {
                        val kb = images.sumOf { it.first.length } / 1024
                        dev.pipilot.app.log.AppLog.i("InputBar", "sendPrompt with ${images.size} image(s), ~${kb}KB base64")
                    }
                    viewModel.sendPrompt(text, images)
                    text = ""
                    pendingImagePayloads.clear()
                },
                enabled = text.isNotBlank() || pendingImagePayloads.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
            }
        }
    }
}

@Composable
private fun StatusStrip(ui: UiState) {
    val model = ui.state?.model
    val text = buildString {
        append(model?.displayName ?: "未选择模型")
        ui.state?.thinkingLevel?.let { append(" · $it") }
        ui.statsText?.let { append(" · $it") }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(settings: ConnectionSettings, viewModel: PiViewModel, onDismiss: () -> Unit) {
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
            Text("SSH 连接", style = MaterialTheme.typography.titleMedium)
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
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
            OutlinedTextField(
                value = profileName, onValueChange = { profileName = it },
                label = { Text("主机名(保存为多主机配置)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.host, onValueChange = { draft = draft.copy(host = it) },
                    label = { Text("主机") }, modifier = Modifier.weight(2f), singleLine = true,
                )
                OutlinedTextField(
                    value = draft.port, onValueChange = { draft = draft.copy(port = it) },
                    label = { Text("端口") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            OutlinedTextField(
                value = draft.user, onValueChange = { draft = draft.copy(user = it) },
                label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipSimple("密码", draft.authType == "password") { draft = draft.copy(authType = "password") }
                FilterChipSimple("私钥", draft.authType == "key") { draft = draft.copy(authType = "key") }
            }
            if (draft.authType == "password") {
                OutlinedTextField(
                    value = draft.password, onValueChange = { draft = draft.copy(password = it) },
                    label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            } else {
                OutlinedTextField(
                    value = draft.privateKey, onValueChange = { draft = draft.copy(privateKey = it) },
                    label = { Text("私钥(PEM 全文)") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = draft.keyPassphrase, onValueChange = { draft = draft.copy(keyPassphrase = it) },
                    label = { Text("私钥口令(可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            Spacer(Modifier.size(12.dp))
            Text("pi 启动", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.piCommand, onValueChange = { draft = draft.copy(piCommand = it) },
                label = { Text("启动命令") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = draft.workDir, onValueChange = { draft = draft.copy(workDir = it) },
                label = { Text("工作目录(可选)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.size(16.dp))
            Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.saveProfile(profileName, draft)
                    onDismiss()
                }) { Text("保存") }
                if (viewModel.ui.value.connected) {
                    OutlinedButton(onClick = {
                        viewModel.saveProfile(profileName, draft)
                        viewModel.disconnect()
                        onDismiss()
                    }) { Text("保存并断开") }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text("运行日志(报 bug 时复制发我)", style = MaterialTheme.typography.titleMedium)
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
    // logSeq 变化即重读
    val text = remember(ui.logSeq) { viewModel.getLogText() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                viewModel.refreshLogView()
                expanded = !expanded
            }) { Text(if (expanded) "收起日志" else "查看日志") }
            OutlinedButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.ifBlank { "(空)" }))
            }) { Text("复制") }
            TextButton(onClick = { viewModel.clearLog() }) { Text("清空") }
        }
        if (expanded) {
            Spacer(Modifier.size(8.dp))
            SelectionContainer {
                Text(
                    text.ifBlank { "(暂无日志)" },
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
            Text("会话(session 文件)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "点按切换 · 长按删除",
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
                    Text("正在列出会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (ui.sessions.isEmpty()) {
                Text(
                    "在 ${ui.sessionsDir ?: "~/.pi/agent/sessions"} 下没有找到会话",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                items(ui.sessions, key = { it.path }) { s ->
                    // 不能用 TextButton+combinedClickable 叠加:按钮内部自带 clickable,
                    // 主分发内层先收到事件,长按松手被按钮当普通点击消费,onLongClick 永远不触发。
                    // 普通 Text 只挂一个 combinedClickable,点按/长按才各自生效。
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
            title = { Text("删除会话？") },
            text = { Text("${target.name}\n\n将永久删除该 session 文件,不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(target.path)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RenameDialog(currentName: String, viewModel: PiViewModel, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("留空则清除名称,显示文件名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setSessionName(input.trim())
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ExtensionDialog(d: UiDialog, onAnswer: (DialogAnswer) -> Unit) {
    var input by remember(d.id) { mutableStateOf(d.prefill ?: "") }
    AlertDialog(
        onDismissRequest = { onAnswer(DialogAnswer.Cancel) },
        title = { Text(d.title.ifBlank { "pi 请求输入" }) },
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
                    TextButton(onClick = { onAnswer(DialogAnswer.Confirm(false)) }) { Text("否") }
                    TextButton(onClick = { onAnswer(DialogAnswer.Confirm(true)) }) { Text("是") }
                }
                "input", "editor" -> TextButton(onClick = { onAnswer(DialogAnswer.Value(input)) }) { Text("确定") }
                else -> TextButton(onClick = { onAnswer(DialogAnswer.Cancel) }) { Text("关闭") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(DialogAnswer.Cancel) }) { Text("取消") }
        },
    )
}
