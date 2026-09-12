# 给 deb 机器的 pi 配置 b.ai 和 opencode 两个 provider

## 现状

- **本地 zcode**（`~/.zcode/v2/config.json`）已有两个自定义 provider：
  - **b.ai**：anthropic 协议，`https://api.b.ai/v1`，key `sk-eyrbh...`，模型 `glm-5.3-flash`（reasoning、1M ctx、text+image）
  - **opencode**：OpenAI 协议，`https://opencode.ai/zen/v1`，key `sk-DTim...`，模型 `muse-spark-1.3-contributor-free`（1M ctx、text+image）
- **deb 机器**：pi 0.85.1（npm 全局），配置目录 `~/.pi/agent/`。已有 qiniullm provider（默认 gpt-5.6-luna），**不动它、不改默认模型**。
- pi 官方文档（vendor/pi-upstream）确认：
  - opencode（OpenCode Zen）是**内置 provider**，凭据放 `~/.pi/agent/auth.json` 的 `"opencode"` 键即可
  - b.ai 用 `~/.pi/agent/models.json` 自定义 provider，`api: "anthropic-messages"`
  - models.json 打开 `/model` 时自动重载，不用重启 pi

## 实施步骤

### 1. 读取 deb 现有配置（先看再改）
```bash
ssh deb 'cat ~/.pi/agent/models.json ~/.pi/agent/auth.json 2>/dev/null; ls ~/.pi/agent/; pi --version'
ssh deb 'pi --list-models 2>/dev/null | grep -i "opencode\|muse"'
```
- 确认 qiniullm 配置长什么样（避免覆盖）；确认 pi 内置 opencode catalog 是否已含 `muse-spark-1.3-contributor-free`（含则 opencode 不用写 models.json，只写 auth.json）。
- 改动前先备份：`cp models.json models.json.bak-<date>`（auth.json 同理，若已存在）。

### 2. 写 auth.json（opencode）
用 jq/python 合并写入（保留已有条目，权限 0600）：
```json
{ "opencode": { "type": "api_key", "key": "sk-DTimEPNZRV5pdgXCy2G50XgoZCoY1KJBmj9olF9ZZHexvveuZxkEfx4BBI0dZUaI" } }
```

### 3. 写 models.json（b.ai，以及 opencode 模型条目如需要）
合并进现有 JSON（保留 qiniullm）：
```json
{
  "providers": {
    "b-ai": {
      "name": "b.ai",
      "baseUrl": "<见第 4 步验证>",
      "api": "anthropic-messages",
      "apiKey": "sk-eyrbhrtpmviu41ya4natcvpulc4xvpus",
      "models": [{
        "id": "glm-5.3-flash",
        "name": "GLM-5.3 Flash",
        "reasoning": true,
        "input": ["text", "image"],
        "contextWindow": 1000000,
        "maxTokens": 128000,
        "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 }
      }]
    },
    "opencode": {
      "models": [{
        "id": "muse-spark-1.3-contributor-free",
        "reasoning": true,
        "input": ["text", "image"],
        "contextWindow": 1000000,
        "maxTokens": 128000,
        "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 }
      }]
    }
  }
}
```
（opencode 段仅在 `pi --list-models` 没有该模型时才加；内置 opencode 的 baseUrl 不用写。）

### 4. 验证 b.ai 端点形态
本地 zcode 的 baseURL 带 `/v1`，但 pi 的 anthropic-messages 按 SDK 风格在 baseUrl 后拼 `/v1/messages`，需在 deb 上实测确定 baseUrl 写法：
```bash
ssh deb 'curl -sS -o /dev/null -w "%{http_code}\n" https://api.b.ai/v1/messages -X POST -H "x-api-key: sk-eyrbh..." -H "anthropic-version: 2023-06-01" -d "{}"'
```
- 返回 400/请求体错误 → 端点是 `/v1/messages`，models.json 里 baseUrl 写 `https://api.b.ai`（pi 自动补 `/v1/messages`）；必要时 grep pi-ai dist 源码确认拼接规则。
- 若需代理才能访问 → 给 pi 进程设 `HTTPS_PROXY`（pi 支持），并说明。

### 5. 端到端验证（遵守“设备上验证后才算修复”）
```bash
ssh deb 'pi -p --provider b-ai --model glm-5.3-flash "reply with just OK"'
ssh deb 'pi -p --provider opencode --model muse-spark-1.3-contributor-free "reply with just OK"'
```
- 按输出调 compat：如 thinking 报错考虑 `allowEmptySignature`/thinkingLevelMap；工具流报错考虑 `supportsEagerToolInputStreaming: false`。
- `pi --list-models` 确认两个 provider 的模型出现在列表里。

### 6. 收尾
- 不改默认模型、不动 qiniullm、不动 tmux 里的 pi 会话（models.json 会自动重载；auth.json 若不生效则提示用户重启该会话）。
- 更新记忆 `deb-machine-pi-setup.md`：新增两个 provider 的配置位置与端点验证结论。

## 备注与风险

- **写 key 到 deb 是本次明确要求**，覆盖早前“用户自己 /login 输 key”的约定，仅限这两个 provider。
- deb 只有 ~1GB 内存、磁盘 73% 满：本方案只写文件 + 短命令验证，无安装、无长驻进程。
- b.ai / opencode.ai 都不在 gfwdomains.txt，预计 deb 可直连；若不通按第 4 步走代理并告知。
- 失败回滚：恢复 `.bak` 备份即可。