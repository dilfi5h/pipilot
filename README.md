# PiPilot — 手机遥控 pi.dev 的 Android 客户端

PiPilot 是一个原生 Android(Kotlin + Jetpack Compose)应用,通过 SSH 在你的开发机/服务器上启动
[pi coding agent](https://pi.dev) 的 RPC 模式(`pi --mode rpc`),把 TUI 里的对话、工具执行、
模型切换、会话管理搬到手机上。

## 下载 APK

**方式一(推荐):GitHub Releases** — 推送 `v*` tag 时 CI 自动打包并发布:

- 最新 release:https://github.com/dilfi5h/pipilot/releases/latest
- 或本仓库 Releases 页下载 `PiPilot-vX.Y.Z-debug.apk`

**方式二:Actions artifact** — 任意 commit 可在
[Actions → Build & Release APK](https://github.com/dilfi5h/pipilot/actions/workflows/release.yml) 的 artifact 里取 APK。

**方式三:本地构建**(JDK 17 + Android SDK 35 + Gradle 8.9):

```bash
gradle assembleDebug   # 或 ./gradlew 若仓库含 wrapper
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

## 工作原理

```
┌─────────────┐   SSH (sshj)    ┌──────────────────────────┐
│  PiPilot    │ ── exec ──────▶ │  pi --mode rpc           │
│  (Android)  │ ◀─ stdout ───── │  (JSONL 事件流 / 响应)     │
└─────────────┘   stdin ──────▶ └──────────────────────────┘
```

- App 用 [sshj](https://github.com/hierynomus/sshj) 建立 SSH 连接,`exec` 启动 `pi --mode rpc`
- RPC 协议:严格 JSONL(仅 `\n` 分帧),命令写入 stdin,`response` + 事件流读自 stdout
- 协议文档:[`docs/rpc.md`](docs/rpc.md)(自 pi 仓库同步)

## 文档

- **[设计文档](docs/DESIGN.md)** — 架构决策、协议实现要点(JSONL 分帧、流式拼装、
  请求-响应关联)、SSH 层、安全考量与 Roadmap
- **[RPC 协议参考](docs/rpc.md)** — pi 官方 RPC 模式文档全文(命令/事件/扩展 UI 子协议)

## 功能(MVP)

- **核心对话**:发 prompt、流式接收回复(text_delta 拼装)、abort 中止、
  流式中发送自动走 follow-up 排队、queue_update 展示排队消息
- **工具执行可视化**:tool_execution_start/update/end 渲染成工具卡片,实时显示
  bash/read/write 等工具的参数与累计输出;直接 `bash` 命令输出也有终端风格卡片
- **模型切换**:get_available_models 列表 + set_model;thinking level 调节(set_thinking_level)
- **会话管理**:新建会话(new_session)、列出远端 `~/.pi/agent/sessions/*.jsonl` 并切换
  (switch_session)、加载历史(get_messages)、会话统计(get_session_stats:tokens/上下文占用)
- **扩展 UI 桥接**:pi 扩展弹出的 select/confirm/input 对话框(extension_ui_request)
  映射为 Android 原生 AlertDialog,回答通过 extension_ui_response 回传

## 使用

1. 在手机上安装 APK,打开 ⚙ 设置:
   - **主机 / 端口 / 用户名**:你的开发机 SSH 信息
   - **认证**:密码,或 PEM 私钥(支持口令)
   - **启动命令**:默认 `pi --mode rpc`;可加 `--provider` / `--model` / `--no-session` 等
   - **工作目录**:pi 启动前 `cd` 到的目录(项目根目录)
2. 点"连接"。连接成功后历史消息自动加载。
3. 输入框发消息;agent 流式执行时输入框自动变为"排队(follow-up)模式",
   ⏹ 按钮发送 abort;顶部菜单切换模型 / thinking level / 会话。

## 远端机要求

```bash
# pi 已安装且在 PATH 中(npm i -g @earendil-works/pi-coding-agent)
pi --version
# 会话目录存在(用过一次 pi 就有)
ls ~/.pi/agent/sessions/
```

注意:RPC 模式不继承 TUI 的终端渲染,但扩展、skills、prompt 模板全部可用;
TUI 专属命令(如 `/settings`)在 RPC 模式下不生效。

## 安全说明

- 当前版本 SSH host key 校验使用 `PromiscuousVerifier`(信任所有主机),适合个人内网,
  公网使用建议后续在设置里加 host key 固定(fingerprint 校验)
- 密码/私钥存储在本机 Datastore(私有目录),不上传;APK 未启用 backup

## 已知限制 / Roadmap

- [ ] 流式中 turn 结束后 `live` 气泡与最终气泡的去重细调
- [ ] 会话树(/tree)、fork/clone、get_entries 增量游标
- [ ] 图片附件(prompt 的 images 字段)
- [ ] export_html 并在手机上预览
- [ ] Host key 固定、SSH agent 转发、多主机配置
- [ ] 通知栏保活 / 断线自动重连
