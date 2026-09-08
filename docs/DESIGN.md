# PiPilot 设计文档

> PiPilot 是一个 Android 原生客户端,通过 pi.dev coding agent 的 RPC 模式,
> 在手机上远程操控跑在你开发机/服务器上的 pi TUI。本文记录设计思路、
> 关键实现方法和踩过的协议坑。协议参考:[docs/rpc.md](rpc.md)(自 pi 官方仓库同步)。

## 1. 目标与约束

**目标**:把 pi 的核心体验——对话、工具执行过程、模型切换、会话管理——搬到手机上,
并且不影响桌面 TUI 的使用(不是复刻 TUI,而是另一块"屏幕")。

**约束**:

- pi 是 Node 进程,TUI 直接跑在终端里;Android 没有原生 Node 运行时
  (Termux 方案可行但安装链路复杂、后台保活差,被否决)。
- pi 官方提供四种接入方式:交互 TUI / print+JSON / **RPC(stdio JSONL)** / SDK。
  对非 Node 客户端,RPC 是唯一进程级稳定接口;SDK 需要 Node,print 模式无流式交互。
- 手机端不应该承担 agent 的计算/网络(LLM API)职责,pi 应该跑在
  网络环境好、有完整工具链(bash/git/编辑器)的机器上。

## 2. 架构决策

### 2.1 远程机器 + SSH exec(而非本机 Termux / WebSocket 桥)

三个候选:

| 方案 | 优点 | 缺点 |
|---|---|---|
| **SSH exec 远程 pi** | 无需服务端组件;复用现有 SSH 基础设施;pi 原生跑在 Linux 机器 | 需要 SSH 库;断线恢复要自己做 |
| Termux 本机 | 完全离线 | Node/Android 兼容链路脆弱;Android 后台限制;工程量大 |
| WebSocket 桥接服务 | 多设备共享、推送友好 | 要额外写并部署一个常驻桥接组件 |

选 SSH 的核心理由:**零服务端部署**——只要你有一台能 SSH 的机器、上面装了 pi,
app 就能工作。SSH exec 通道本身就是全双工 stdin/stdout 管道,与 RPC 模式的
stdio 接口天然对齐:

```
┌─────────────┐  SSH (sshj, exec channel)  ┌─────────────────────────┐
│  PiPilot    │ ──── stdin (JSONL) ──────▶ │ pi --mode rpc           │
│  (Android)  │ ◀─── stdout (JSONL) ────── │ (JSON 事件/响应流)        │
└─────────────┘                            └─────────────────────────┘
```

### 2.2 技术栈:Kotlin + Jetpack Compose(原生)

- JSONL 流处理、SSH(sshj)、协程 Flow 在 JVM 生态里都是成熟组件,不需要
  platform channel 桥接(Flutter/RN 方案的隐性成本)。
- 聊天 UI 是 Compose 的舒适区:LazyColumn + 流式文本 + 局部更新。
- 包体积小(minSdk 26,无 WebView/JS 引擎)。

### 2.3 分层

```
ui/        Compose 界面(PiScreen)+ PiViewModel(唯一状态出口)
chat/      StreamReducer:RPC 事件流 → 可渲染条目(ChatItem)的归约器
rpc/       PiProtocol(数据模型 + 命令构造)、PiRpcClient(JSONL 读写、请求-响应关联)
ssh/       SshConnector:sshj 连接、auth(password/PEM)、exec 通道
settings/  SettingsStore(Datastore 持久化连接配置)
```

依赖方向单向:`ui → chat → rpc ← ssh`。`PiViewModel` 负责把 ssh 通道和 rpc
客户端装配起来(`connect()`),UI 层不接触任何 IO。

## 3. 协议层实现要点

### 3.1 严格 JSONL 分帧(pi 文档明确要求的坑)

pi 的 RPC 模式使用 **strict JSONL**:只认 `\n` 作为记录分隔符,但允许行尾带
`\r`。经典错误是使用 `BufferedReader.readLine()` ——它按"行终止符集合"切行,
部分实现会把 `U+2028`/`U+2029`(合法的 JSON 字符串内容)当行尾,导致
LLM 输出包含这类字符时消息被截断、JSON 解析失败。

实现(`PiRpcClient.readLoop`):手工按 `\n` 分帧——块读取(8K char buffer)
+ 逐字符扫描,遇到 `\n` 即切出一条记录,`removeSuffix("\r")` 后解析。
写方向同理:每条命令显式 `\n` 结尾,**禁止** `newLine()`(Windows 平台会写成
`\r\n`,虽然 pi 容忍尾部 `\r`,但不要赌所有版本的行为)。

### 3.2 请求-响应关联

所有命令支持可选 `id`;带 `id` 的命令会收到带相同 `id` 的
`{"type":"response",...}`。事件流与响应共享同一条 stdout,所以:

- `PiRpcClient` 维护 `ConcurrentHashMap<String, CompletableDeferred<PiResponse>>`;
- `request(line, id, timeout)` 发送前注册 deferred,response 到达时按 id complete;
- 没带 id 的命令(如 `steer`/`abort`)fire-and-forget,结果通过事件体现;
- stdout 关闭或超时会让所有 pending deferred 以失败收场,不会悬挂。

### 3.3 流式拼装(没有快照,只有增量)

`message_update` 事件**不携带累计消息快照**,只有 delta 子事件
(`text_delta`/`thinking_delta`/`toolcall_start|delta|end`),需要客户端按
`contentIndex` 自行拼装。`StreamReducer` 的状态机:

- `agent_start` 重置 live 缓冲(正文 + 思考 + toolcall 参数表);
- `text_delta` 追加、`text_end` 用权威全文覆盖(防止丢 delta 后漂移);
- `message_end.message` 是最终态,据此固化一条 `AssistantText(final)`;
- `tool_execution_start` 时固化当前 live 文本并清空——因为后续正文属于
  下一段输出,不清会重复渲染;
- **`tool_execution_update.partialResult` 是累计输出**(不是增量),直接
  整卡替换,这也是文档建议的显示方式。

### 3.4 UI 条目归约(merge 策略)

`StreamReducer` 产出的是"增量操作",`PiViewModel.mergeItems` 把它合并进
聊天列表:`key == "live"` 的气泡原地替换;`ToolCard` 按 `toolCallId` 原地更新
(args 保留首发值,update 事件不带 args);`BashOutput` 追加 delta;
其余追加。这保证 LazyColumn 以 `key` 做 diff 时不会整表重组。

### 3.5 流式中发送消息

RPC 规定:agent 正在 streaming 时,`prompt` 必须显式带
`streamingBehavior`(否则返回错误)。PiPilot 的策略:空闲时发裸 `prompt`;
流式中发 `streamingBehavior: "followUp"`(等 agent 完整跑完再送达),把
"打断"的决定权留给用户——输入框在流式期间显示"排队(follow-up)",
同时提供独立的 abort(⏹)按钮发 `abort` 命令。`queue_update` 事件驱动
"排队中 N 条"提示。

### 3.6 扩展 UI 子协议

pi 扩展可通过 `ctx.ui.select()/confirm()/input()` 弹窗,RPC 模式下它变成
`extension_ui_request` 事件,**阻塞等待** stdin 上的 `extension_ui_response`。
PiPilot 把它映射成原生 `AlertDialog`(`PiViewModel` 拦截该事件转成
`UiDialog` state),三种应答形态严格按文档:`value`(select/input)、
`confirmed`(confirm)、`cancelled`(任意)。fire-and-forget 类
(notify/setStatus/setWidget 等)MVP 暂时只显示扩展错误,不逐条建模。

## 4. SSH 层

- sshj 0.40 + 显式注册完整版 BouncyCastle(Android 内置的是裁剪版 BC,
  Ed25519/PKCS8 解析会缺类)。
  依赖注意:sshj 同时传递 `bcprov-jdk15on` 和 `bcprov-jdk18on`,必须
  exclude 旧模块,否则重复类编译失败。
- 认证支持密码和 PEM 私钥(`PKCS8KeyFile`,口令可选)。
- `exec` 通道启动 `(cd <workdir> &&) <piCommand>`,默认
  `pi --mode rpc`;stderr 持续 drain 并打日志(避免缓冲区塞死阻塞 pi)。
- host key 校验目前是 `PromiscuousVerifier`(信任所有)。个人内网可接受;
  公网使用建议加 fingerprint 固定(见 Roadmap)。
- 会话列表不走 RPC,直接 `runQuick` 执行
  `ls -t ~/.pi/agent/sessions/*.jsonl | head -30`(按修改时间排序),
  再用 `switch_session` 加载。

## 5. 状态与生命周期

- 单一 `UiState`(StateFlow)驱动全 UI;`PiViewModel` 持有 ssh 会话和 rpc
  client,ViewModel 销毁时断开通道。
- 断线(stdout EOF / IO 异常)→ `ConnectionState.Closed(reason)` → UI 显示
  断开原因,不清聊天内容(重连后 `get_messages` 恢复)。
- RPC 进程随 SSH 通道生灭:断开即远端 pi 退出。会话文件持久化在远端,
  重连可无损恢复,这是选择"无状态连接 + 远端 session 文件"的原因。

## 6. 安全考量

- 凭据(密码/PEM/口令)存于 app 私有 Datastore,`allowBackup=false`,
  不出设备。
- 密码字段、错误信息中的主机细节均不在日志打印。
- 已知妥协:PromiscuousVerifier(见 §4)。改进路径:设置页粘贴
  `sha256:<fingerprint>`,sshj 的 `FingerprintVerifier` 一行可换。

## 7. 打包与发布

本地构建一次约 1–4 分钟(Windows 冷缓存),正式发布走 GitHub Actions
(`.github/workflows/release.yml`):推 `v*` tag 自动用 JDK17 + Gradle 8.9 +
android-actions/setup-android 构建 debug APK,上传 workflow artifact,并以
softprops/action-gh-release 附到 GitHub Release,release notes 自动生成。
`gradle-wrapper` 有意不入库(本机有预装 Gradle;CI 由 setup-gradle 提供确定
版本),仓库更干净,也避免 wrapper jar 的二进制 review 问题。

## 8. Roadmap

- [ ] host key 指纹固定(FingerprintVerifier + 设置页)
- [ ] 断线自动重连 + 指数退避
- [ ] 会话树:`get_tree`/`get_fork_messages`/`fork`/`clone`(对应 TUI /tree)
- [ ] `get_entries` + `since` 游标做增量历史同步
- [ ] 图片附件(`prompt.images`,ImageContent base64)
- [ ] `export_html` + 手机端预览
- [ ] 通知栏常驻保活 / 多主机配置 / release 签名构建
