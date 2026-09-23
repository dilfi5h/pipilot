# Chat UI preview

纯 UI 改动（气泡、折叠、Markdown、间距、密度）**先在这里用 HTML 定稿，再改 Android**。不要一上来打 APK。

主仓库说明见根目录 [`README.md`](../../README.md) 的 **Chat UI preview** 一节。

## 流程

1. 从跑 pi 的机器拉真实会话（jsonl 在 `~/.pi/agent/sessions/`，按工作目录分子目录，例如 `--root--`）：

   ```bash
   mkdir -p tools/chat-preview/sessions
   scp 'host:~/.pi/agent/sessions/*/*.jsonl' tools/chat-preview/sessions/
   ```

2. 转成和 App 同结构的条目（user / assistant / tool / bash）：

   ```bash
   python3 tools/chat-preview/parse_sessions.py
   ```

3. 浏览器打开 `tools/chat-preview/index.html`（或 `open tools/chat-preview/index.html`）。左边切会话、全部展开、藏 thinking；右边是手机框。改 CSS / 折叠默认，刷新即可。

4. 观感定稿后再搬进 Compose：`PiScreen` / `MarkdownText` / `ChatCollapse`。

会话 jsonl 和生成的 `fixtures.js` 已 gitignore，不要提交。

## 当前 mock 默认

- thinking 是独立弱卡片（虚线框），不塞进助手气泡
- 颜色：read 青、write 琥珀、edit 紫；其它 tool 共用石板灰；bash 仍是深色终端
- 历史 thinking / tool / bash 输出默认折叠，点标题展开
- thinking 预览取首行；tool / bash 输出预览取末行
- bash 命令行始终显示全文（绿色 `$ cmd`，可换行）；折叠只收输出
- read 到 `.md` / `.markdown` / `.mdx` 时，展开按 Markdown 渲染
- 助手气泡 wrap-content，无 320dp 上限
- Copy 只是占位，不写剪贴板
- 助手气泡时间戳旁 mock TTFT / tok/s（首轮有 TTFT，工具后续写只有 tok/s）；左侧可关「显示 TTFT / tok/s」，可关「插入流式气泡样例」
