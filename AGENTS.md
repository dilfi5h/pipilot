# PiPilot 开发流程

新功能按下面顺序走。**某一步失败或用户不满意，退回上一步改，不要跳步往前冲。**

仓库没有 `gradlew`，用本机 Gradle 8.9：`gradle …`。签名在仓库根 `pipilot-release.keystore` + `keystore.properties`（均不入库）；本地 `assembleRelease` 和已装包同一把钥匙，才能覆盖安装。

## 1. 讨论设计

先把问题和方案说清楚，再动代码。

- 协议 / 架构：对照 [`docs/rpc.md`](docs/rpc.md)、[`docs/DESIGN.md`](docs/DESIGN.md)。pi RPC 没有的字段不要假装服务端会给。
- 写清：数据从哪来、算在哪一侧、显示在哪、历史 / 流式 / 失败怎么表现。
- 设计定了再改 [`docs/DESIGN.md`](docs/DESIGN.md)（实现细节、协议坑、产品决策）。不要只改代码不留记录。

这一步没达成一致，不要做 HTML，更不要改 App。

## 2. 如果涉及 UI：先做 HTML 预览

气泡、折叠、Markdown、间距、密度、脚注这类纯 UI，**不要一上来打 APK**。

流程见 [`tools/chat-preview/README.md`](tools/chat-preview/README.md) 和根目录 README 的 **Chat UI preview**：

1. 需要真实会话时，从跑 pi 的机器拉 jsonl 到 `tools/chat-preview/sessions/`，再 `python3 tools/chat-preview/parse_sessions.py`。
2. 打开 [`tools/chat-preview/index.html`](tools/chat-preview/index.html)，在手机框里改 CSS / 结构，刷新看效果。
3. 用户看过、观感定稿后，才搬进 Compose。

`sessions/*.jsonl` 和生成的 `fixtures.js` 已 gitignore，不要提交。

非 UI（RPC、SSH、心跳、签名）可以跳过这一步。HTML 用户说不行，就继续改 HTML，不要同时改 App。

## 3. 修改 App 代码

把定稿方案落到 Kotlin / Compose：

- UI：`PiScreen` / `MarkdownText` / `ChatCollapse` 等，对齐 HTML，不要自行发挥一套。
- 协议与状态：`PiProtocol` / `PiRpcClient` / `StreamReducer` / `PiViewModel`。
- 能单测的计算和状态机要补测试（`app/src/test/…`），用 `gradle :app:testDebugUnitTest --offline`。
- 编译警告里与本次无关的 deprecated 不必顺手清。

测试或行为不对：停在这一层修，不要带着红测试去打包。

## 4. 本地打 release 包

真机验证用 **signed release**（R8 minify + shrink），debug 包过不了 ProGuard / 覆盖安装。

```bash
gradle :app:testDebugUnitTest --offline
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

打包失败（R8、资源、签名）回到第 3 步改代码或 ProGuard，不要让用户装坏包。

## 5. 用户真机测试

把 APK 交给用户在已装 PiPilot 的手机上覆盖安装。Agent 不要假设本机有 `adb`。

请用户确认：新功能、回归（发消息、流式、工具卡、重连）。有问题记下复现，**退回对应步骤**（观感 → 第 2 步 HTML；逻辑 / 崩溃 → 第 3 步；装不上 → 第 4 步），再重新打包，不要直接推。

## 6. 推送代码

真机通过后再提交、推远程。

- 不要提交：`keystore.properties`、`*.keystore`、`tools/chat-preview/sessions/`、`fixtures.js`、本地 APK。
- 提交信息写清做了什么，不要空的 `update`。
- **任何情况下不要自行打 tag 或者修改版本号** 只有用户明确说「打 tag」时才打。日常真机包用第 4 步的本地 APK。

推送被拒或 CI 挂了：按报错回到第 3 / 4 步，不要强推。
